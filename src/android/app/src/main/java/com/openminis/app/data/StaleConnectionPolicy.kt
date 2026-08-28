package com.openminis.app.data

/**
 * Pure decision: after a stream attempt fails, should the shared LLM
 * connection pool be evicted before retrying on the SAME provider?
 *
 * The failure that motivates this is the TTFB stale-connection hang: a
 * request is written into a pooled HTTP/2 socket that a VPN/proxy has
 * silently dropped, no response headers ever arrive, and the watchdog
 * cancels the call after [STALE_TTFB_MARKER]s. Retrying without evicting
 * hands the same dead socket to the next attempt, which hangs again. This
 * is especially visible right after a compaction: the summary call + DB
 * writes leave the chat socket idle long enough for the proxy to reap it,
 * so the FIRST post-compaction turn writes into the corpse.
 *
 * Kept as a pure function (no OkHttp / Android types) so the retry decision
 * is unit-testable; the caller performs the actual eviction side effect.
 */
object StaleConnectionPolicy {

    /** Substring that identifies the TTFB stale-connection transient error. */
    const val STALE_MARKER = "no response from server"

    /**
     * Additional connection-level failure signatures that also warrant
     * dropping the pooled socket before retrying. Unlike a plain 5xx (a live
     * socket delivered a real HTTP error — keep it), these mean the physical
     * HTTP/2 connection is deteriorating: the server/proxy reset the stream
     * (RST_STREAM), sent GOAWAY, or the socket ended mid-response. Retrying on
     * the SAME coalesced socket tends to hit the same fault, so we force a
     * fresh dial. Matched case-insensitively as substrings of the error text
     * OkHttp/our mapError surfaces (e.g. "stream was reset: INTERNAL_ERROR",
     * "unexpected end of stream", "connection reset", "shutdown").
     */
    private val CONNECTION_FAULT_MARKERS = listOf(
        "stream was reset",
        "unexpected end of stream",
        "connection reset",
        "connection closed",
        "shutdown",
        "goaway",
        "broken pipe",
        "software caused connection abort",
    )

    /**
     * True when [errorDetail] is the stale-connection signal. Matched on the
     * watchdog's message so any provider funnelling through the same
     * TransientError text benefits.
     */
    fun isStaleConnection(errorDetail: String?): Boolean =
        errorDetail?.contains(STALE_MARKER, ignoreCase = true) == true

    /**
     * True when [errorDetail] indicates the underlying HTTP/2 connection is
     * faulty (reset / GOAWAY / abrupt close) — the socket, not just the
     * request, is suspect.
     */
    fun isConnectionFault(errorDetail: String?): Boolean {
        val d = errorDetail ?: return false
        return CONNECTION_FAULT_MARKERS.any { d.contains(it, ignoreCase = true) }
    }

    /**
     * Evict before retrying whenever the socket itself is suspect — either the
     * TTFB stale-connection hang OR a connection-level fault (stream reset /
     * GOAWAY / abrupt close). Plain transient errors (5xx, rate blips) keep
     * the pool: their sockets are still healthy.
     */
    fun shouldEvictBeforeRetry(errorDetail: String?): Boolean =
        isStaleConnection(errorDetail) || isConnectionFault(errorDetail)

    /**
     * Idle window (ms) past which a pooled LLM socket is treated as
     * potentially reaped by a NAT/proxy. Mirrors
     * NetworkMonitor.STALE_IDLE_THRESHOLD_MS — kept here as the single source
     * of truth so the pre-flight decision is unit-testable without Android
     * types. Below the shortest realistic NAT idle-timeout (cellular NAT often
     * reaps at 30-60s), above the back-to-back agent-loop turn gap so warm
     * sockets are still reused.
     */
    const val STALE_IDLE_THRESHOLD_MS: Long = 20_000L

    /**
     * Pure pre-flight decision: given the idle duration since the last
     * observed LLM byte, should the pool be evicted BEFORE the next request?
     * [idleMs] < 0 or an unknown-activity sentinel (caller passes < 0) means
     * "never observed activity" → don't evict (nothing pooled to be stale).
     */
    fun shouldEvictBeforeRequest(
        idleMs: Long,
        thresholdMs: Long = STALE_IDLE_THRESHOLD_MS,
    ): Boolean = idleMs >= 0 && idleMs >= thresholdMs
}
