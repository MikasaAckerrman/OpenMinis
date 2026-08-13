package com.openminis.app.data

/**
 * [T-context-pressure-blind] Resolves "how full is the context right now?"
 * from the two signals available, in the right order.
 *
 * ## The bug this fixes
 *
 * `_lastTurnContextTokens` is populated ONLY from a successful response's
 * usage block. On a session that has started failing — the exact session the
 * user needs help with — no response ever arrives, so the counter sits at its
 * initial 0 forever. Everything gated on it then silently does nothing:
 *
 *  - [ContextMaintenance.decide] saw `contextTokens = 0`, concluded "pressure
 *    unknown", and returned LIGHT on every single send. No full compaction, no
 *    rescue, no matter how many turns passed or how large the history was.
 *  - [RescueAdvisor.shouldSuggestRescue] computed `0 / window = 0%`, decided
 *    the session was small, and suppressed the "/rescue" hint on precisely the
 *    vague transport errors it exists to explain.
 *  - Reopening the app is worse than it looks: the counter is in-memory, so a
 *    reload resets it to 0 even for a session that previously reported 190K.
 *
 * The result is what the user reported: the whole history kept being sent,
 * including the part that was never compacted, because nothing recognised
 * there was pressure to act on.
 *
 * ## The fix
 *
 * A local character-count estimate is always available (it walks the history
 * we are about to send). It is cruder than the provider's tokeniser, so the
 * provider's number wins WHEN WE HAVE IT — but "crude" beats "blind": being
 * wrong by 20% still triggers the right tier, while 0 triggers nothing.
 *
 * Pure logic so the precedence is unit-tested.
 */
object ContextPressure {

    /**
     * @param usageTokens last value reported by the provider (0 = never got one)
     * @param estimatedTokens local char-based estimate of the current history
     * @return the number the maintenance gates should reason about
     */
    fun resolve(usageTokens: Int, estimatedTokens: Int): Int = when {
        usageTokens > 0 -> usageTokens
        estimatedTokens > 0 -> estimatedTokens
        else -> 0
    }

    /** True when [resolve] fell back to the estimate — worth logging once. */
    fun isEstimated(usageTokens: Int, estimatedTokens: Int): Boolean =
        usageTokens <= 0 && estimatedTokens > 0
}
