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
