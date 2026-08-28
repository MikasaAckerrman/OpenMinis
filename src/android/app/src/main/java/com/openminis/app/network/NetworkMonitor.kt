package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Monitors network connectivity changes using ConnectivityManager.NetworkCallback.
 * Evicts the OkHttp connection pool on network transitions to prevent stale connections.
 */
class NetworkMonitor {

    enum class NetworkStatus {
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * [T-android-stale-conn-retry-hang] App-wide ConnectionPool shared by
         * every long-lived LLM provider OkHttpClient (OpenAI / Anthropic /
         * Gemini) — OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * [evictConnectionPool] actually reach provider connections:
         * previously eviction only covered the single client registered via
         * [start], and MinisApp registers none, so eviction was a no-op.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry — requests wrote into it
         * and hung forever waiting for response headers.
         */
        // [T-android-stale-conn-per-host] keepAlive lowered 5min → 60s → 30s.
        // A local VPN/proxy AND a bare cellular/WiFi NAT both silently reap
        // idle TCP mappings (cellular NAT idle-timeout is often 30-60s), and
        // the drop is half-open: no RST arrives, so the pooled socket looks
        // alive and the next request writes into the void. A shorter keepAlive
        // means WE close the idle socket (clean FIN → next request dials a
        // fresh one) before the NAT can strand it.
        //
        // 30s is deliberately AT/BELOW the shortest realistic NAT idle window
        // and above [StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS] (20s), so
        // the two defences layer instead of fighting: the per-host pre-flight
        // check ([evictLLMConnectionsIfIdle]) handles the 20-30s band using
        // observed activity, and this keepAlive is the bookkeeping-independent
        // backstop that bounds staleness even when no stamp exists (cold
        // start, host-map eviction). Back-to-back agent-loop turns are seconds
        // apart, so the warm socket is still reused on the hot path.
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 30, java.util.concurrent.TimeUnit.SECONDS,
        )

        /**
         * [T-android-stale-conn-per-host] Per-host last-activity bookkeeping
         * for the pre-flight stale-socket check.
         *
         * Why per-host and not one global stamp: pooled TCP sockets are keyed
         * by host, so staleness is a per-host property. With several sessions
         * running concurrently against DIFFERENT providers, a single global
         * stamp let a busy session's traffic mask an idle one — the idle
         * session's own socket had been reaped by the NAT, but the global stamp
         * looked fresh, the pre-flight check skipped eviction, and the request
         * was written into the corpse and hung until the TTFB watchdog fired.
         *
         * Keyed by the provider's `throttleKey`, which is already the endpoint
         * host (LlmDispatchGate.keyForUrl(basePath)) — reused deliberately so
         * there is ONE host-identity notion in the codebase, not two.
         *
         * Logic lives in [com.openminis.app.data.HostActivityTracker] (pure,
         * unit-tested); this holds only the production clock binding.
         */
        private val hostActivity = com.openminis.app.data.HostActivityTracker(
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )

        /**
         * Record that LLM network activity just happened on [hostKey]
         * (any-thread safe). Called on every stream chunk.
         */
        fun markLLMActivity(hostKey: String) {
            hostActivity.mark(hostKey)
        }

        /**
         * Proactive stale-socket defence, PER HOST. If more than
         * [idleThresholdMs] has elapsed since the last observed byte from
         * [hostKey], evict the pool so the next request to that host dials a
         * FRESH socket instead of writing into a possibly half-open corpse and
         * hanging until the TTFB watchdog fires.
         *
         * This is the load-bearing fix: it makes the stale pooled socket
         * structurally impossible regardless of the NAT's idle timeout, while
         * still reusing warm sockets on the hot path (agent-loop turns fire
         * back-to-back within the threshold and skip eviction). Especially
         * covers the FIRST post-compaction / post-pause turn, and — since the
         * idle window is now per-host — the case where a BUSY session on
         * another provider used to mask an idle session's dead socket.
         *
         * Eviction granularity note: OkHttp's ConnectionPool exposes no
         * per-host eviction, so we evict the whole idle set. That is safe and
         * cheap — `evictAll()` closes only connections with no active calls,
         * so a concurrently streaming session is never interrupted; other
         * hosts merely re-dial on their next turn.
         *
         * @param hostKey provider endpoint host (see [markLLMActivity]).
         * @return true if the pool was evicted.
         */
        fun evictLLMConnectionsIfIdle(
            hostKey: String,
            idleThresholdMs: Long = com.openminis.app.data.StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS,
        ): Boolean {
            if (!hostActivity.shouldEvictAndClear(hostKey, idleThresholdMs)) return false
            sharedLLMConnectionPool.evictAll()
            return true
        }

        /**
         * Idle window past which a pooled LLM socket is treated as potentially
         * reaped. Delegates the numeric threshold to
         * [com.openminis.app.data.StaleConnectionPolicy] (the unit-testable
         * single source of truth); kept here only as a re-export for callers
         * that already reference NetworkMonitor.
         */
        const val STALE_IDLE_THRESHOLD_MS: Long =
            com.openminis.app.data.StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS

        /**
         * Evict all idle sockets from the shared LLM pool on demand. Called
         * from the stream retry path when the failure is a stale-connection
         * TTFB hang (see [com.openminis.app.data.StaleConnectionPolicy]): the
         * next attempt must open a fresh socket instead of writing into the
         * dead pooled one again. Safe to call from any thread — evictAll()
         * only walks the pool's idle set.
         */
        fun evictLLMConnectionsNow() {
            sharedLLMConnectionPool.evictAll()
        }

        /**
         * [T-android-dns-await] Process-wide connectivity mirror.
         *
         * Why a companion-level flow when the instance already exposes
         * [status]: the retry path in ChatViewModel has no handle on the
         * MinisApp-owned NetworkMonitor instance, and threading one through
         * would mean touching every construction site. The instance publishes
         * every transition here, so any caller can await connectivity without
         * new plumbing. There is exactly one monitor per process, so a single
         * static mirror cannot disagree with itself.
         *
         * Defaults to `true` deliberately: if the monitor was never started
         * (unit tests, early startup), awaiting must not block a request that
         * would otherwise have worked.
         */
        private val _connectivity = MutableStateFlow(true)

        /** Publishes connectivity transitions; called only by the instance. */
        internal fun publishConnectivity(connected: Boolean) {
            _connectivity.value = connected
        }

        /**
         * Suspends until the device reports usable connectivity, or
         * [timeoutMs] elapses.
         *
         * Used before retrying a name-resolution failure: a DNS lookup that
         * failed because Doze had parked the radio will fail again instantly
         * on a fixed 1s/2s/4s ladder, burning every attempt while the link is
         * still down. Waiting for the link to actually return converts that
         * into a successful retry.
         *
         * @return true if connectivity is available (immediately or after
         *   waiting), false if [timeoutMs] elapsed while still offline.
         */
        suspend fun awaitConnectivity(timeoutMs: Long): Boolean =
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                _connectivity.first { it }
                true
            } ?: false
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    /**
     * Single write path for [_status] so the process-wide connectivity mirror
     * ([publishConnectivity], awaited by [awaitConnectivity]) can never drift
     * out of sync with the instance flow. Every transition — initial state and
     * all three NetworkCallback edges — goes through here.
     */
    private fun setStatus(newStatus: NetworkStatus) {
        _status.value = newStatus
        publishConnectivity(newStatus == NetworkStatus.CONNECTED)
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /**
     * Background scope for DNS-refresh side effects. Kept off the callback
     * thread so ConnectivityManager isn't held up by file I/O.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
     *
     * @param context Application or activity context.
     * @param client Optional shared OkHttpClient for connection pool eviction.
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        okHttpClient = client
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

        val cm = connectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        // Set initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _status.value = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        publishConnectivity(_status.value == NetworkStatus.CONNECTED)
        Log.d(TAG, "Initial network status: ${_status.value}")

        // Mirror iOS NetworkMonitor.swift:23-26 — do an immediate DNS write so
        // resolv.conf is populated before the first NetworkCallback fires. The
        // callback is async and can lag by hundreds of ms on cold start.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val previousStatus = _status.value
                setStatus(NetworkStatus.CONNECTED)
                if (previousStatus == NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "Network transition: DISCONNECTED -> CONNECTED")
                    evictConnectionPool()
                }
                // Always refresh sandbox DNS on availability — an interface
                // swap (Wi-Fi → cellular) can fire onAvailable without a
                // prior onLost, and the new interface carries new DNS servers.
                refreshSandboxDns("onAvailable")
            }

            override fun onLost(network: Network) {
                setStatus(NetworkStatus.DISCONNECTED)
                Log.d(TAG, "Network transition: CONNECTED -> DISCONNECTED")
                evictConnectionPool()
                // Rewrite resolv.conf even when disconnected so it falls back
                // to 8.8.8.8 / 8.8.4.4 instead of sitting stale with a DNS
                // server that's no longer reachable.
                refreshSandboxDns("onLost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val newStatus = if (hasInternet) NetworkStatus.CONNECTED else NetworkStatus.DISCONNECTED
                if (newStatus != _status.value) {
                    Log.d(TAG, "Network capabilities changed: ${_status.value} -> $newStatus")
                    setStatus(newStatus)
                    evictConnectionPool()
                    refreshSandboxDns("onCapabilitiesChanged")
                }
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Unregisters the network callback. Should be called during cleanup.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Callback was not registered: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        okHttpClient = null
        appContext = null
    }

    /**
     * Evicts all idle connections from the OkHttp connection pools
     * to prevent stale connection reuse after a network change.
     * Always evicts [sharedLLMConnectionPool] (all LLM provider clients),
     * plus the optional client registered via [start].
     */
    private fun evictConnectionPool() {
        sharedLLMConnectionPool.evictAll()
        okHttpClient?.connectionPool?.evictAll()
        Log.d(TAG, "OkHttp connection pools evicted (shared LLM pool + registered client)")
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Runs on IO so we don't block the ConnectivityManager
     * callback thread with file I/O. Safe to call before the rootfs has been
     * extracted — [RootfsManager.refreshDns] no-ops when the rootfs is missing.
     *
     * Mirrors iOS NetworkMonitor.swift:26,60 which calls refreshDns() on every
     * NWPath update so already-running shells pick up the new nameservers the
     * next time they resolve a hostname (musl's getaddrinfo re-reads
     * resolv.conf on each lookup — no cache to invalidate).
     */
    private fun refreshSandboxDns(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                RootfsManager.getInstance(ctx).refreshDns()
                Log.d(TAG, "[DNS] sandbox resolv.conf refreshed ($reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "[DNS] refresh failed ($reason): ${t.message}")
            }
        }
    }
}
