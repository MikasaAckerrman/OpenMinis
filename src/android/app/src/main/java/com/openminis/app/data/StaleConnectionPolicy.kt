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
     * True when [errorDetail] is the stale-connection signal. Matched on the
     * watchdog's message so any provider funnelling through the same
     * TransientError text benefits.
     */
    fun isStaleConnection(errorDetail: String?): Boolean =
        errorDetail?.contains(STALE_MARKER, ignoreCase = true) == true

    /**
     * Evict before retrying only for the stale-connection case. Other
     * transient errors (5xx, generic network blips) do not benefit from
     * dropping live pooled sockets, so leave the pool intact for them.
     */
    fun shouldEvictBeforeRetry(errorDetail: String?): Boolean =
        isStaleConnection(errorDetail)
}
