package com.openminis.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-auto-mistake] MNL-style automatic mistake capture (MistakeNotebookLearning
 * concept, adapted 25.09): a FAILED tool call is appended to today's memory
 * log WITHOUT the model deciding to save it — the PostToolUse-hook equivalent.
 * Failures then compound: the daily-memory fragment (injected into every
 * request) and memory_get surface them automatically, and the model distils
 * the recurring ones into core-memory blocks / memory_write notes at its own
 * pace (capture = automatic, distillation = model-curated — the division our
 * whole memory architecture already follows).
 *
 * Entry format: ONE line — the 8K daily-fragment budget is line-capped, so a
 * single compact line keeps the signal dense:
 *   ⚠ MISTAKE <HH:mm:ss> <toolName>: <error excerpt ≤240 chars>
 *
 * Throttle: identical (toolName + first 80 error chars) within 60s collapses
 * into one entry — a retry loop (CI poll, grep miss) must not spam the log.
 * Thread-safe: parallel tool failures append under a monitor (rare event,
 * negligible contention). Fire-and-forget caller (executeTool, IO dispatcher).
 */
object AutoMistakeLog {

    private const val THROTTLE_MS = 60_000L
    private const val ERROR_EXCERPT_CHARS = 240

    @Volatile
    private var dir: File? = null

    /** Last emit time per (toolName + error-prefix); in-process dedup state. */
    private val lastEmit = HashMap<String, Long>()

    /** Capture the storage dir. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        primeDir(File(context.applicationContext.filesDir, "minis-global/memory"))
    }

    /** Test seam: JVM tests point the log at a TemporaryFolder. */
    internal fun primeDirForTest(directory: File?) {
        synchronized(this) {
            dir = directory
            lastEmit.clear()
        }
    }

    /**
     * Append one mistake line for a failed tool call. Returns true when the
     * entry was written (false = throttled duplicate, no dir, or empty error).
     */
    @Synchronized
    fun capture(toolName: String, toolTitle: String, errorOutput: String, sessionId: String): Boolean {
        val target = dir ?: return false
        val error = errorOutput.trim()
        if (error.isEmpty()) return false
        val now = System.currentTimeMillis()
        val key = "$toolName|${error.take(80)}"
        lastEmit[key]?.let { if (now - it < THROTTLE_MS) return false }
        lastEmit[key] = now
        // Bound the map: a long session with many distinct tools.
        if (lastEmit.size > 64) {
            val cutoff = now - THROTTLE_MS
            lastEmit.entries.removeIf { it.value < cutoff }
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val stamp = Date(now)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(stamp)
        val line = buildString {
            append("<!-- ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(stamp)} --> ")
            append("⚠ MISTAKE ${fmt.format(stamp)} $toolName")
            if (toolTitle.isNotBlank() && toolTitle != toolName) append(" [$toolTitle]")
            append(": ")
            append(error.replace('\n', ' ').take(ERROR_EXCERPT_CHARS))
            append("  (sid=${sessionId.take(8)})")
        }
        return runCatching {
            File(target, "$day.md").appendText(line + "\n")
            true
        }.getOrDefault(false)
    }
}
