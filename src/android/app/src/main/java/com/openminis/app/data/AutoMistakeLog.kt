package com.openminis.app.data

import com.openminis.app.data.repository.MemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-auto-mistake] MNL-style automatic mistake capture (MistakeNotebookLearning
 * concept, adapted 25.09): a FAILED tool call is written into today's memory
 * log WITHOUT the model deciding to save it — the PostToolUse-hook equivalent.
 * Failures then compound: the daily-memory fragment (injected into every
 * request) and memory_get surface them automatically, and the model distils
 * the recurring ones into core-memory blocks / memory_write notes at its own
 * pace (capture = automatic, distillation = model-curated).
 *
 * v2 (review fix): routes through [MemoryRepository.writeMemory] — the SAME
 * write path as the model's own memory_write. v1 appended to the file tail:
 * (a) the repo stores newest-FIRST and the daily fragment reads head lines,
 * so tail-appended mistakes would NEVER surface; (b) the repo rewrites the
 * whole file (read→prepend→write) — a concurrent repo write would silently
 * erase appended tail lines. Going through the repo fixes both: correct
 * placement, the repo's entry framing, and the same serialization domain as
 * every other daily-log writer (memory_write is not a parallel tool, so the
 * capture inside executeTool cannot overlap it).
 *
 * Throttle: identical (toolName + first 80 error chars) within 60s collapses
 * into one entry — a retry loop (CI poll, grep miss) must not spam the log.
 * Entry format: ONE content line (the 8K daily fragment is line-capped):
 *   ⚠ MISTAKE <HH:mm:ss> <toolName> [title]: <error excerpt ≤240> (sid=…)
 */
object AutoMistakeLog {

    private const val THROTTLE_MS = 60_000L
    private const val ERROR_EXCERPT_CHARS = 240

    /** Last emit time per (toolName + error-prefix); in-process dedup state. */
    private val lastEmit = HashMap<String, Long>()

    /** Test seam: reset the throttle state between tests. */
    internal fun resetForTest() {
        synchronized(this) { lastEmit.clear() }
    }

    /**
     * Write one mistake line for a failed tool call. Returns true when the
     * entry landed (false = throttled duplicate, empty error, or the repo's
     * write failed — the repo's own result string is the error surface).
     */
    @Synchronized
    fun capture(
        repo: MemoryRepository,
        toolName: String,
        toolTitle: String,
        errorOutput: String,
        sessionId: String,
    ): Boolean {
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
        val stamp = Date(now)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(stamp)
        val line = buildString {
            append("⚠ MISTAKE $time $toolName")
            if (toolTitle.isNotBlank() && toolTitle != toolName) append(" [$toolTitle]")
            append(": ")
            append(error.replace('\n', ' ').take(ERROR_EXCERPT_CHARS))
            append("  (sid=${sessionId.take(8)})")
        }
        // The repo prepends (newest-first) with its <!-- ts --> framing and
        // returns "Memory saved to …" on success / "Error: …" on failure.
        return !repo.writeMemory(line).startsWith("Error")
    }
}
