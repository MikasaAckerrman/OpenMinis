package com.openminis.app.data

/**
 * [T-effective-size-honesty] Decides whether a history is large enough that
 * a relay "content-blocked" rejection is plausibly a SIZE filter rather
 * than genuine moderation.
 *
 * Callers must pass the EFFECTIVE payload size (what actually goes on the
 * wire after compaction: summary + live tail), never the raw DB history.
 * The raw history stays huge after /compact (compaction adds a marker; it
 * does not delete rows), so a raw-based check told already-compacted
 * sessions "payload too big, run /compact" — advice that lied and masked
 * the real cause (a relay-side content filter rejecting even small
 * bodies). The user's methodology: measure by what the relay receives.
 *
 * Threshold semantics preserved from the original isRawHistoryLarge():
 * half the model's context window, capped at 24k tokens (the conservative
 * absolute floor for relays with an unknown window), floored at 8k so
 * ordinary small sessions never trip it.
 */
object HistorySizeGate {

    /**
     * @param effectiveTokens approximate token count of the EFFECTIVE payload
     *   (the messages that will actually be serialized into the request).
     * @param contextWindowTokens the model's advertised context window, or
     *   null/zero when unknown.
     */
    fun isLarge(
        effectiveTokens: Int,
        contextWindowTokens: Int?,
    ): Boolean {
        val window = contextWindowTokens?.takeIf { it > 0 }
        // Half the window is "large enough that size is a credible cause".
        // Absolute floor 24k covers unknown-window relays where a big payload
        // still trips a size filter well before any real context limit.
        val threshold = window?.let { it / 2 } ?: 24_000
        return effectiveTokens >= minOf(threshold, 24_000).coerceAtLeast(8_000)
    }
}
