package com.openminis.app.data

/**
 * Classifies provider/transport error messages so the AI-compaction path can
 * decide whether a failed request is worth retrying with a smaller input.
 *
 * Extracted verbatim from the former `RescueAdvisor` when the local rescue
 * digest was removed: the two detector functions below are used by the LIVE
 * AI-compaction split-retry logic (`generateCompactSummaryWithSplitting`) and
 * have nothing to do with the deleted local-digest feature. They are kept
 * Android-free for unit testing.
 *
 * The hard part these detectors handle: an over-large request often does NOT
 * come back as a clean "context length exceeded". A body the upstream refuses
 * to finish reading surfaces as a dropped connection, an empty response, or a
 * TTFB-watchdog timeout ("no response from server") — errors that look like
 * network trouble while the network is fine. So an oversized request may need
 * split-and-retry even when the error text is vague.
 */
object TransportErrorClassifier {
    private val EXPLICIT_SIZE_MARKERS = listOf(
        "too many tokens", "context length", "max_tokens", "content is too long",
        "exceeds the model", "request too large", "prompt is too long",
        "token limit", "context window", "payload too large", "413",
        "string too long", "too large for",
    )

    private val VAGUE_FAILURE_MARKERS = listOf(
        "no response from server", "empty response", "connection", "closed",
        "reset", "eof", "timeout", "timed out", "stream", "broken pipe",
        "unexpected end", "502", "503", "504", "520", "524",
    )

    /** True when the error text explicitly names a size/context-length problem. */
    fun isExplicitSizeError(message: String): Boolean {
        val m = message.lowercase()
        return EXPLICIT_SIZE_MARKERS.any { m.contains(it) }
    }

    /**
     * True for a generic/transient transport failure (dropped connection,
     * empty response, gateway 5xx, TTFB timeout) — the kind of vague error an
     * upstream emits when it refuses to finish reading an oversized body.
     */
    fun isVagueTransportFailure(message: String): Boolean {
        val m = message.lowercase()
        return VAGUE_FAILURE_MARKERS.any { m.contains(it) }
    }
}
