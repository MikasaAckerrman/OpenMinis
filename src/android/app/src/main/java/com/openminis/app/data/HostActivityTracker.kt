package com.openminis.app.data

/**
 * [T-android-stale-conn-per-host] Per-host last-activity bookkeeping for the
 * pre-flight stale-socket check.
 *
 * Pooled TCP sockets are keyed by host, so "has this connection sat idle long
 * enough that a NAT may have reaped it?" is a PER-HOST question. The earlier
 * implementation kept ONE app-wide timestamp, which broke with concurrent
 * sessions on different providers: a busy session stamped the global clock on
 * every chunk, so an idle session's pre-flight check saw "activity 1s ago",
 * skipped eviction, wrote into its own long-dead socket and hung until the TTFB
 * watchdog fired. Keying by host makes each provider's idle window independent.
 *
 * Pure and clock-injected (no Android types) so the bookkeeping — including the
 * bounded-map eviction — is unit-testable; the caller performs the actual
 * OkHttp pool eviction side effect.
 *
 * Thread-safe: all mutation happens under [lock]. Contention is negligible
 * (one op per stream chunk, one per request) and the critical sections are
 * map operations only.
 *
 * @param maxHosts cap on tracked hosts; when full, the least-recently-active
 *   entry is dropped so a user with many configured providers can't grow this
 *   without bound.
 * @param nowMs monotonic clock source (elapsedRealtime in production).
 */
class HostActivityTracker(
    private val maxHosts: Int = DEFAULT_MAX_HOSTS,
    private val nowMs: () -> Long,
) {
    companion object {
        /** Default cap on tracked hosts. */
        const val DEFAULT_MAX_HOSTS = 32
    }

    private val lock = Any()
    private val lastActivityByHost = HashMap<String, Long>()

    /** Record that a byte was observed from [hostKey] just now. */
    fun mark(hostKey: String) {
        synchronized(lock) {
            if (lastActivityByHost.size >= maxHosts && !lastActivityByHost.containsKey(hostKey)) {
                lastActivityByHost.entries.minByOrNull { it.value }
                    ?.let { lastActivityByHost.remove(it.key) }
            }
            lastActivityByHost[hostKey] = nowMs()
        }
    }

    /**
     * Pre-flight decision for [hostKey]. Returns true when this host's socket
     * has been idle at least [thresholdMs] and the caller should therefore
     * evict before dialing.
     *
     * A host with no recorded activity returns false: nothing was ever pooled
     * for it, so there is no corpse to avoid. (The pool's own keepAlive is the
     * backstop for that case.)
     *
     * On a true result the stamp is CLEARED — it described a socket that no
     * longer exists, so a second check before any new byte arrives must not
     * report staleness again.
     */
    fun shouldEvictAndClear(
        hostKey: String,
        thresholdMs: Long = StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS,
    ): Boolean {
        synchronized(lock) {
            val last = lastActivityByHost[hostKey] ?: return false
            val idleMs = nowMs() - last
            if (!StaleConnectionPolicy.shouldEvictBeforeRequest(idleMs, thresholdMs)) return false
            lastActivityByHost.remove(hostKey)
            return true
        }
    }

    /** Number of tracked hosts. Test/diagnostics only. */
    fun trackedHostCount(): Int = synchronized(lock) { lastActivityByHost.size }
}
