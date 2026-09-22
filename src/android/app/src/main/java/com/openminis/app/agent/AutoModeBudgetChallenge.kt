package com.openminis.app.agent

/**
 * [T-auto-mode] Pure policy for the autonomous loop's "don't stop early"
 * guard. User contract 23.09.2026: «выдал 2 часа = работай все 2 часа» —
 * a work budget is a MANDATE, not just a ceiling. When the model reports
 * TASK_COMPLETE while a large slice of the budget remains, the manager
 * challenges the claim once per run (bounded: the model can insist by
 * repeating TASK_COMPLETE in the challenge continuation).
 */
object AutoModeBudgetChallenge {
    /**
     * Below this much remaining time a completion is accepted without a
     * challenge: the budget is nearly spent, finishing now is what the
     * user asked for.
     */
    const val CHALLENGE_THRESHOLD_MS: Long = 15L * 60 * 1000

    /** Hard cap: challenges per run (the model always wins by insisting). */
    const val MAX_CHALLENGES = 2

    /**
     * Pure gate. [remainingMs] < 0 means no timer armed — a completion
     * without a time budget is accepted as-is (nothing to honor).
     */
    fun shouldChallenge(remainingMs: Long, challengesUsed: Int): Boolean =
        remainingMs > CHALLENGE_THRESHOLD_MS && challengesUsed < MAX_CHALLENGES

    /**
     * The challenge continuation. Deliberately gives the model an OUT:
     * repeating TASK_COMPLETE ends the run (respect the model's judgment),
     * but it must do so in the SAME reply — after having just been asked
     * "is it really the best it can be?".
     */
    fun prompt(remainingMs: Long, challengesUsed: Int): String {
        val minutes = remainingMs / 60_000
        return "⟳ Авто-режим · проверка завершения #$challengesUsed — бюджет ещё не истёк: осталось $minutes мин.\n" +
            "Прежде чем остановиться, честно ответь себе: план выполнен НАИЛУЧШИМ образом, или есть что улучшить?\n" +
            "- Можно углубить (ещё тесты, ещё ревью, ещё полировка, упущенные нюансы)? → продолжай работу, VERIFY-блок как обычно, затем TASK_COMPLETE по готовности.\n" +
            "- Дальнейшая работа реально бессмысленна/вредна? → повтори TASK_COMPLETE прямо в этом ответе — цикл остановится без вопросов."
    }
}
