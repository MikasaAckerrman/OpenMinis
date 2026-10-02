package com.openminis.app.network

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-dns-pin] DNS resolution cache with a positive-result TTL.
 *
 * Why this exists — measured on the user's device (02.10 log, mobile
 * data, CGNAT): 34 of 48 lookups for dashscope.aliyuncs.com took
 * >300ms, with outliers of 3.7s / 17s / 23s / 30s / 53s. OkHttp does
 * no DNS caching of its own (it defers to the OS resolver, whose
 * cache holds entries only for the authoritative TTL — often short
 * for CDN-backed API hosts), so EVERY new connection paid the slow
 * lookup again. Worst case a lookup exhausted its internal retries
 * and the whole call failed with SSLHandshakeException("connection
 * closed") — 26 connectFailed lines for that single host in one day.
 *
 * Policy: successful resolutions are pinned for [TTL_MS] (5 minutes).
 * Failures are NEVER cached — a transient resolver hiccup must not
 * poison the host. A pinned entry serves the request instantly; the
 * next lookup after expiry refreshes it. Negative lookup results are
 * not cached either (InetAddress would have thrown — nothing to store).
 *
 * Thread-safety: ConcurrentHashMap; a stale-but-valid entry is
 * preferable to a thundering herd of slow lookups, so no per-key
 * locking on misses (the OS resolver serialises them anyway).
 */
object CachedDns : Dns {

    private const val TTL_MS = 5L * 60 * 1000

    private data class Entry(
        val addresses: List<InetAddress>,
        val atMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val hit = cache[hostname]
        if (hit != null && now - hit.atMs < TTL_MS) {
            return hit.addresses
        }
        // [T-dns-retry] The operator resolver (CGNAT, mobile data) fails
        // PER ATTEMPT: "No address associated with hostname" surfaces
        // while the very next lookup succeeds. One quick retry (300ms
        // apart) converts those single-shot misses into hits; only a
        // genuinely dead resolution propagates to OkHttp.
        var resolved: List<InetAddress> = emptyList()
        var last: Exception? = null
        for (attempt in 0 until 2) {
            try {
                resolved = Dns.SYSTEM.lookup(hostname)
                break
            } catch (e: Exception) {
                last = e
                if (attempt == 0) try { Thread.sleep(300) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        if (resolved.isEmpty()) throw (last ?: java.net.UnknownHostException(hostname))
        cache[hostname] = Entry(resolved, now)
        return resolved
    }

    /** Test hook + future network-change invalidation point. */
    fun clearForTest() = cache.clear()
}
