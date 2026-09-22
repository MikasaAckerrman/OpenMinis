package com.openminis.app.offload

/**
 * [T-turn-timer] Pure policy of the turn timer ("работай ровно N минут"):
 * formatting, urgency lines, the soft-stop contract. The engine lives in
 * ChatViewModel; these rules are pinned off-device.
 *
 * Contract: the agent sees the remaining time on EVERY tool result; when the
 * budget is spent it gets a stop instruction and a short grace window, then
 * tools are refused outright so the model MUST write its final summary
 * (done / not done / what remains) instead of starting new work.
 */
object TurnTimerPolicy {

    /** Tool calls still allowed AFTER expiry, so an in-flight step can land. */
    const val GRACE_TOOL_CALLS = 2

    fun remainingMs(deadlineMs: Long, nowMs: Long): Long = deadlineMs - nowMs

    /** "43:12", "1:02:33" or "EXPIRED" for <= 0. */
    fun format(remainingMs: Long): String {
        if (remainingMs <= 0) return "EXPIRED"
        val totalSec = remainingMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /**
     * The line appended to a tool result while the timer runs. Null when the
     * timer is off. After expiry it becomes the stop instruction.
     */
    fun line(remainingMs: Long, totalMs: Long): String {
        val share = if (totalMs > 0) remainingMs.toDouble() / totalMs else 1.0
        val left = format(remainingMs)
        return if (remainingMs > 0) {
            val prefix = if (share <= 0.15) "⏳ [turn timer] $left left — plan to wrap up soon" else "⏳ [turn timer] $left left"
            prefix
        } else {
            "⏳ [turn timer] TIME IS UP — deliver the FINAL summary NOW: what is done, " +
                "what is not done, what remains. Do not start new work or call more tools."
        }
    }

    /** The hard refusal once the grace window is spent. */
    fun refusal(): String =
        "⏳ [turn timer] Expired and the grace window is spent — tools are refused. " +
            "Write your final answer now: DONE / NOT DONE / WHAT REMAINS."
}
