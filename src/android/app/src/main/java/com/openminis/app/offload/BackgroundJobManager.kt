package com.openminis.app.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-android-background-jobs] Manager for fire-and-forget tasks the agent
 * (or any CLI via [android-shizuku-cli job ...]) submits. Mirrors the
 * long-running tasks Claude Code / Cursor can spawn (CI runners, build
 * pipelines) — submit returns immediately with a [jobId], the actual work
 * happens on an app-scoped coroutine backed by [AgentForegroundService]
 * so it survives Activity destroy / process pause, and `status`/`output`/
 * `wait` poll from any session.
 *
 * Why this is separate from [ExecutionCoordinator]: EC.execute() suspends
 * the caller until the command finishes. For an agent doing "submit push,
 * submit build, check both" we need non-blocking submit. BJM spawns the
 * EC.execute inside a Job coroutine, drains the lineCallback into a log
 * file, and tracks the exit code for status().
 *
 * **Concurrency model**
 *  - Multiple Jobs run concurrently. Each owns its own coroutine + its
 *    own lineCallback file writer. No global mutex — we trust PRoot to
 *    handle per-session serialization, and Jobs without a sessionId
 *    don't touch any session state.
 *  - One in-memory record per active Job (ConcurrentHashMap), mirrored
 *    to [BackgroundJobStore] at every status transition so a process
 *    restart can reconcile orphans.
 *
 * **Lifetime**
 *  - Jobs survive Activity destroy because the scope is app-scoped
 *    ([SupervisorJob] + [Dispatchers.IO]), not viewModelScope.
 *  - Jobs survive process death only for their metadata + log tail.
 *    The PRoot process itself dies with the app — on next boot,
 *    [BackgroundJobStore.pruneAndOrphan] resets Running/Pending to
 *    Failed with reason "process restarted".
 */
object BackgroundJobManager {
    private const val TAG = "BackgroundJobManager"

    /** App-scoped. Outlives any Activity, VM, or agent session. */
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** jobId → in-memory state for active jobs. */
    private val active = ConcurrentHashMap<String, JobRecord>()

    /** jobId → coroutine Job handle (so cancel() can kill it). */
    private val handles = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Per-job mutex so status()/output() reads see consistent state. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private data class JobRecord(
        var job: BackgroundJob,
        var writer: OutputStreamWriter?,
    )

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        // BackgroundJobStore.init must be called separately (it's already
        // pattern: SessionBadgeStore.init + CompactProgressStore.init in
        // MinisApp.onCreate). We don't call it from here to avoid a
        // double-init race during static <clinit>.
    }

    /**
     * Submit a new background job. Returns immediately with the assigned
     * jobId. The job starts as Pending and transitions to Running after
     * [ExecutionCoordinator.execute] hands it a PRoot shell, then to
     * Done/Cancelled/Failed on completion.
     *
     * @param name user-facing label (also shown in the foreground notification)
     * @param command shell command to execute
     * @param sessionId optional PRoot session id. If null, runs in a
     *   throwaway sh -c (no persistent state). When provided, [ExecutionCoordinator]
     *   routes through the session's PersistentShell.
     */
    fun submit(
        context: Context,
        name: String,
        command: String,
        sessionId: String? = null,
    ): String {
        BackgroundJobStore.init(context)
        val id = "job_${UUID.randomUUID().toString().take(12)}"
        val now = System.currentTimeMillis()

        // Initial log file — empty so subsequent appends work from offset 0.
        BackgroundJobStore.logFile(context, id).createNewFile()

        val initial = BackgroundJob(
            id = id,
            name = name.take(80),
            command = command.take(4096),
            sessionId = sessionId,
            status = JobStatus.Pending,
            exitCode = null,
            createdMs = now,
            startedMs = null,
            finishedMs = null,
            logSizeBytes = 0L,
            failureReason = null,
        )
        BackgroundJobStore.save(context, initial)

        val lock = Mutex()
        locks[id] = lock
        active[id] = JobRecord(
            job = initial,
            writer = OutputStreamWriter(
                FileOutputStream(BackgroundJobStore.logFile(context, id), true),
                StandardCharsets.UTF_8,
            ),
        )

        // Promote foreground service so the Job survives screen-off / Doze.
        try {
            AgentForegroundService.startService(
                context = context.applicationContext,
                sessionCount = active.size.coerceAtLeast(1),
                toolStatus = "Job: ${initial.name}",
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ForegroundService start failed: ${t.message}")
            // Not fatal — the Job will still run, just without the persistent
            // notification + foreground lifetime guarantees.
        }

        // Spawn the Job coroutine.
        val handle = jobScope.launch {
            runJob(context, id, command, sessionId)
        }
        handles[id] = handle
        AppLogger.info(TAG, "submitted $id name=$name session=$sessionId")
        return id
    }

    /** Synchronous snapshot of a Job's state. Returns null if unknown id. */
    fun status(context: Context, jobId: String): BackgroundJob? {
        BackgroundJobStore.init(context)
        active[jobId]?.let { return it.job }
        return BackgroundJobStore.load(context, jobId)
    }

    /**
     * Read up to [maxBytes] of log starting at [fromOffset]. Returns the
     * text and the new offset (== fromOffset + bytesRead). The log file
     * is line-oriented UTF-8; partial last line is included verbatim
     * (it's the caller's job to remember offset across calls).
     */
    fun output(
        context: Context,
        jobId: String,
        fromOffset: Long = 0,
        maxBytes: Int = 16 * 1024,
    ): Pair<String, Long> {
        BackgroundJobStore.init(context)
        val f = BackgroundJobStore.logFile(context, jobId)
        if (!f.exists()) return "" to fromOffset
        val len = f.length()
        if (fromOffset >= len) return "" to fromOffset
        val toRead = (len - fromOffset).coerceAtMost(maxBytes.toLong())
        val bytes = f.inputStream().use { input ->
            input.skip(fromOffset)
            input.readNBytes(toRead.toInt())
        }
        return String(bytes, StandardCharsets.UTF_8) to (fromOffset + bytes.size)
    }

    /**
     * Suspend until the Job reaches a terminal state (Done/Cancelled/Failed)
     * OR [timeoutMs] elapses. Returns the final state, or the current
     * state if the wait timed out.
     */
    suspend fun wait(context: Context, jobId: String, timeoutMs: Long): BackgroundJob? {
        BackgroundJobStore.init(context)
        val handle = handles[jobId] ?: return status(context, jobId)
        return withTimeoutOrNull(timeoutMs) {
            handle.join()
            status(context, jobId)
        } ?: status(context, jobId)
    }

    /**
     * Cooperative cancel. Marks the Job Cancelled, closes the writer,
     * and cancels the coroutine (which lets PRoot finish its current
     * command but won't accept more). The actual PRoot process keeps
     * running until the command finishes — there's no kill switch for
     * a stdin-based shell in the current PersistentShell API; a future
     * iteration can add `cancel(jobId)` to PersistentShell that SIGINTs
     * the process group.
     */
    fun cancel(context: Context, jobId: String): Boolean {
        BackgroundJobStore.init(context)
        val handle = handles[jobId] ?: return false
        handle.cancel()
        val rec = active[jobId] ?: return true
        runCatching { rec.writer?.flush(); rec.writer?.close() }
        val cancelled = rec.job.copy(
            status = JobStatus.Cancelled,
            finishedMs = System.currentTimeMillis(),
            failureReason = "cancelled via job cancel",
        )
        rec.job = cancelled
        BackgroundJobStore.save(context, cancelled)
        active.remove(jobId)
        handles.remove(jobId)
        locks.remove(jobId)
        maybeStopForeground()
        AppLogger.info(TAG, "cancelled $jobId")
        return true
    }

    /** Snapshot of all known jobs, including terminal ones in storage. */
    fun list(context: Context): List<BackgroundJob> {
        BackgroundJobStore.init(context)
        // Merge in-memory (active) with persisted (terminal + orphan-reset).
        val byId = mutableMapOf<String, BackgroundJob>()
        BackgroundJobStore.list(context).forEach { byId[it.id] = it }
        active.values.forEach { byId[it.job.id] = it.job }
        return byId.values.sortedByDescending { it.createdMs }
    }

    // ─── internals ────────────────────────────────────────────────────────

    private fun runJob(
        context: Context,
        jobId: String,
        command: String,
        sessionId: String?,
    ) {
        val rec = active[jobId] ?: return
        val lock = locks[jobId] ?: return

        lock.withLock {
            val running = rec.job.copy(
                status = JobStatus.Running,
                startedMs = System.currentTimeMillis(),
            )
            rec.job = running
            BackgroundJobStore.save(context, running)
        }

        // Wire the lineCallback to stream stdout/stderr into the log file.
        // Bytes-flushed each line so `output` mid-flight sees fresh content.
        val lineCallback: (String) -> Unit = { line ->
            runCatching {
                val w = rec.writer ?: return@runCatching
                synchronized(w) {
                    w.write(line)
                    w.write("\n")
                    w.flush()
                }
                val newSize = rec.writer?.let { BackgroundJobStore.logFile(context, jobId).length() }
                    ?: rec.job.logSizeBytes
                lock.withLock {
                    rec.job = rec.job.copy(logSizeBytes = newSize)
                }
                BackgroundJobStore.save(context, rec.job)
            }.onFailure {
                AppLogger.warning(TAG, "log write failed for $jobId: ${it.message}")
            }
        }

        val result = runCatching {
            ExecutionCoordinator.execute(
                sessionId = sessionId ?: "__background__",
                command = command,
                timeout = 24L * 60L * 60L * 1000L,  // 24h — long enough for builds
                lineCallback = lineCallback,
            )
        }

        lock.withLock {
            val final: BackgroundJob
            when {
                result.isFailure -> {
                    final = rec.job.copy(
                        status = JobStatus.Failed,
                        finishedMs = System.currentTimeMillis(),
                        failureReason = result.exceptionOrNull()?.message
                            ?: "execute failed without message",
                    )
                }
                else -> {
                    val r = result.getOrThrow()
                    final = rec.job.copy(
                        status = if (r.exitCode == 0) JobStatus.Done else JobStatus.Failed,
                        exitCode = r.exitCode,
                        finishedMs = System.currentTimeMillis(),
                        failureReason = if (r.exitCode == 0) null
                            else "exit=${r.exitCode} durationMs=${r.durationMs}",
                    )
                }
            }
            rec.job = final
            BackgroundJobStore.save(context, final)
            runCatching { rec.writer?.flush(); rec.writer?.close() }
        }

        handles.remove(jobId)
        active.remove(jobId)
        locks.remove(jobId)
        maybeStopForeground(context)
        AppLogger.info(
            TAG,
            "completed $jobId status=${rec.job.status.name} exit=${rec.job.exitCode}",
        )
    }

    private fun maybeStopForeground(context: Context) {
        // When no jobs remain, the foreground service should drop its
        // ongoing notification. The service itself owns its lifecycle; we
        // just stop it when nothing is left to advertise.
        if (active.isEmpty()) {
            runCatching {
                AgentForegroundService.stopService(context.applicationContext)
            }.onFailure {
                AppLogger.warning(TAG, "FGS stop failed: ${it.message}")
            }
        }
    }
}
