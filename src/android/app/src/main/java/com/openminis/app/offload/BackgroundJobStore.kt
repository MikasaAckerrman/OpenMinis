package com.openminis.app.offload

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * [T-android-background-jobs] Persistent metadata store for
 * [BackgroundJobManager]. Two-tier storage:
 *
 *  - metadata → SharedPreferences ("background_job_store", one key per job
 *    id, JSON blob). Cheap to read/write from the IPC handler thread.
 *  - stdout/stderr log → file at `filesDir/jobs/<id>.log`, appended line
 *    by line by the running Job. Persistent across process death so an
 *    "old" Job whose tail survives a restart still shows output.
 *
 * Status transitions are valid monotonic paths:
 *   Pending → Running → Done|Cancelled|Failed
 *
 * On restore, a job last seen in Running or Pending is reset to Failed
 * with a synthetic reason — the kernel killed the PRoot process with the
 * app, no way to know whether it would have succeeded.
 */
data class BackgroundJob(
    val id: String,
    val name: String,
    val command: String,
    val sessionId: String?,
    val status: JobStatus,
    val exitCode: Int?,
    val createdMs: Long,
    val startedMs: Long?,
    val finishedMs: Long?,
    val logSizeBytes: Long,
    val failureReason: String?,
)

enum class JobStatus {
    Pending,
    Running,
    Done,
    Cancelled,
    Failed,
}

object BackgroundJobStore {
    private const val TAG = "BackgroundJobStore"
    private const val PREFS = "background_job_store"
    private const val KEY_INDEX = "_index"
    private const val KEY_JOB_PREFIX = "job_"
    private const val LOG_DIR = "jobs"
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L  // 7 days

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var logDir: File? = null

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        logDir = File(app.filesDir, LOG_DIR).also { it.mkdirs() }
        // On boot: prune index entries older than MAX_AGE_MS AND reset orphan
        // Running/Pending jobs to Failed (the PRoot process died with us).
        pruneAndOrphan(app)
        AppLogger.info(TAG, "init: jobs in store=${allIds().size}")
    }

    /** Save metadata. Caller has already appended log lines to the log file. */
    fun save(context: Context, job: BackgroundJob) {
        val p = prefs ?: init(context).let { prefs!! }
        val json = JSONObject().apply {
            put("id", job.id)
            put("name", job.name)
            put("command", job.command)
            put("sessionId", job.sessionId ?: JSONObject.NULL)
            put("status", job.status.name)
            put("exitCode", job.exitCode ?: JSONObject.NULL)
            put("createdMs", job.createdMs)
            put("startedMs", job.startedMs ?: JSONObject.NULL)
            put("finishedMs", job.finishedMs ?: JSONObject.NULL)
            put("logSizeBytes", job.logSizeBytes)
            put("failureReason", job.failureReason ?: JSONObject.NULL)
        }
        p.edit().putString("$KEY_JOB_PREFIX${job.id}", json.toString()).apply()
        // Maintain an explicit index so `list()` is one read, not a full scan.
        val ids = p.getStringSet(KEY_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (job.id !in ids) {
            ids += job.id
            p.edit().putStringSet(KEY_INDEX, ids).apply()
        }
    }

    fun load(context: Context, jobId: String): BackgroundJob? {
        val p = prefs ?: return null
        val raw = p.getString("$KEY_JOB_PREFIX$jobId", null) ?: return null
        return runCatching { parseJob(raw) }.getOrNull()
    }

    fun delete(context: Context, jobId: String) {
        val p = prefs ?: return
        p.edit().remove("$KEY_JOB_PREFIX$jobId").apply()
        val ids = p.getStringSet(KEY_INDEX, emptySet())?.toMutableSet() ?: return
        if (jobId in ids) {
            ids -= jobId
            p.edit().putStringSet(KEY_INDEX, ids).apply()
        }
        logFile(context, jobId).delete()
    }

    fun list(context: Context): List<BackgroundJob> {
        val p = prefs ?: return emptyList()
        val ids = p.getStringSet(KEY_INDEX, emptySet()) ?: return emptyList()
        return ids.mapNotNull { id -> load(context, id) }
    }

    fun logFile(context: Context, jobId: String): File =
        File(logDir(context), "$jobId.log")

    private fun logDir(context: Context): File =
        logDir ?: File(context.filesDir, LOG_DIR).also { it.mkdirs() }

    private fun allIds(): Set<String> =
        prefs?.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()

    private fun parseJob(raw: String): BackgroundJob {
        val o = JSONObject(raw)
        return BackgroundJob(
            id = o.getString("id"),
            name = o.getString("name"),
            command = o.getString("command"),
            sessionId = if (o.isNull("sessionId")) null else o.optString("sessionId"),
            status = runCatching { JobStatus.valueOf(o.getString("status")) }
                .getOrDefault(JobStatus.Failed),
            exitCode = if (o.isNull("exitCode")) null else o.optInt("exitCode"),
            createdMs = o.optLong("createdMs"),
            startedMs = if (o.isNull("startedMs")) null else o.optLong("startedMs"),
            finishedMs = if (o.isNull("finishedMs")) null else o.optLong("finishedMs"),
            logSizeBytes = o.optLong("logSizeBytes", 0L),
            failureReason = if (o.isNull("failureReason")) null else o.optString("failureReason"),
        )
    }

    private fun pruneAndOrphan(context: Context) {
        val p = prefs ?: return
        val ids = p.getStringSet(KEY_INDEX, emptySet())?.toMutableSet() ?: return
        val now = System.currentTimeMillis()
        for (id in ids.toList()) {
            val job = load(context, id) ?: continue
            // Reset Running/Pending → Failed (the Job's coroutine died with us)
            val needsReset = job.status == JobStatus.Running ||
                job.status == JobStatus.Pending
            val tooOld = (now - job.createdMs) > MAX_AGE_MS
            when {
                needsReset -> {
                    val reset = job.copy(
                        status = JobStatus.Failed,
                        finishedMs = now,
                        failureReason = "process restarted while job was ${job.status.name.lowercase()}",
                    )
                    save(context, reset)
                    AppLogger.warning(TAG, "orphan reset: $id was ${job.status}")
                }
                tooOld -> {
                    delete(context, id)
                    AppLogger.info(TAG, "pruned old job: $id")
                }
            }
        }
    }
}
