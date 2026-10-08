package com.openminis.app.offload

/**
 * [T-subagent-wake-bridge] Pure shaping for the wake-up prompt a background
 * subagent's result injects into the parent session's queue (ZCode
 * semantics: the parent agent continues autonomously when its workers
 * finish — a real continuation turn, not a sticker the model only sees
 * when the human writes next).
 *
 * Kept side-effect-free so the contract is unit-testable: the ViewModel
 * owns the queue write + drain kick (injectWakePrompt), this owns WHAT the
 * wake text looks like.
 */
object SubagentWake {

    /** The report carried inside the wake prompt is capped: the full report
     * already lives in the system-info payload (the ⓘ affordance); the
     * prompt carries the actionable head, keeping the injected user-turn
     * cheap for the model's context diet. */
    const val REPORT_CAP = 6000

    fun promptId(): String =
        "wake_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}"

    fun buildWakeText(role: String, result: String): String {
        val cleanRole = role.trim().lowercase()
        val body = if (result.length > REPORT_CAP) result.take(REPORT_CAP) + "\n…(отчёт усечён — полная версия в системной строке выше)" else result
        return "[Субагент $cleanRole] фоновый прогон завершён.\n\n" +
            "Отчёт:\n$body\n\n" +
            "Продолжай работу с учётом этого результата."
    }
}
