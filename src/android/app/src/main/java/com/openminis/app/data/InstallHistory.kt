package com.openminis.app.data

import android.content.Context
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-build-tracking] Append-only install history stored as JSON in filesDir.
 *
 * On each app start, compares the current [BuildConfig.VERSION_CODE] with the
 * last recorded entry. If they differ (new install or update), appends a new
 * record with the full build provenance (git sha, branch, CI run id, date).
 *
 * No Room table — the data is tiny (a few dozen entries at most), write-once,
 * and never queried by SQL. A plain JSON file avoids schema-migration risk.
 *
 * Read by [com.openminis.app.debug.DebugRPCHandler] via `debug.installHistory`
 * so the agent can see what was installed previously without investigating.
 */
object InstallHistory {

    private const val FILE_NAME = "install_history.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Call from [com.openminis.app.MinisApp.onCreate]. If the current
     * versionCode differs from the last entry (or the log is empty),
     * append a new record. Cheap: one file read + at most one write.
     */
    fun recordIfChanged(context: Context) {
        val entries = read(context)
        val last = entries.optJSONObject(entries.length() - 1)
        val lastCode = last?.optInt("versionCode", -1) ?: -1

        if (lastCode == BuildConfig.VERSION_CODE && last != null) {
            // Same version already recorded — nothing to do.
            return
        }

        val record = JSONObject().apply {
            put("versionCode", BuildConfig.VERSION_CODE)
            put("versionName", BuildConfig.VERSION_NAME)
            put("gitSha", BuildConfig.GIT_SHA)
            put("gitBranch", BuildConfig.GIT_BRANCH)
            put("ciRunId", BuildConfig.CI_RUN_ID)
            put("buildDate", BuildConfig.BUILD_DATE)
            put("installedAt", System.currentTimeMillis())
            put("isDebugBuild", BuildConfig.DEBUG)
        }
        entries.put(record)
        file(context).writeText(entries.toString())
    }

    /**
     * Return the full install history as a [JSONArray].
     * Each entry: {versionCode, versionName, gitSha, gitBranch, ciRunId, buildDate, installedAt, isDebugBuild}
     */
    fun read(context: Context): JSONArray {
        val f = file(context)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    /**
     * Return the last install record, or null if empty.
     */
    fun lastRecord(context: Context): JSONObject? {
        val entries = read(context)
        return if (entries.length() == 0) null else entries.optJSONObject(entries.length() - 1)
    }
}
