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
        // [T-android-stale-conn-proactive] keepAlive lowered 5min → 60s.
        // A local VPN/proxy AND a bare cellular/WiFi NAT both silently reap
        // idle TCP mappings (cellular NAT idle-timeout is often 30-60s), and
        // the drop is half-open: no RST arrives, so the pooled socket looks
        // alive and the next request writes into the void. A shorter keepAlive
        // means WE close the idle socket (clean FIN → next request dials a
        // fresh one) before the NAT can strand it. This alone is not a
        // guarantee — any fixed keepAlive is a bet against an unknown NAT
        // timeout — so it is a backstop for [evictLLMConnectionsIfIdle].
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 60, java.util.concurrent.TimeUnit.SECONDS,
        )

        /**
         * Monotonic timestamp (elapsedRealtime) of the last observed LLM
         * network activity — set whenever a stream chunk / response byte
         * arrives (see ChatViewModel). Used by [evictLLMConnectionsIfIdle] to
         * decide, BEFORE a request, whether the pooled socket has sat idle
         * long enough that a NAT/proxy may have silently reaped it.
         */
        @Volatile
        private var lastLLMActivityAtMs: Long = 0L

        /** Record that LLM network activity just happened (any-thread safe). */
        fun markLLMActivity() {
            lastLLMActivityAtMs = android.os.SystemClock.elapsedRealtime()
        }

        /**
         * Proactive stale-socket defence. If more than [idleThresholdMs] has
         * elapsed since the last observed LLM byte, evict the pool so the next
         * request dials a FRESH socket instead of writing into a possibly
         * half-open corpse and hanging until the TTFB watchdog fires.
         *
         * This is the load-bearing fix: it makes the stale pooled socket
         * structurally impossible regardless of the NAT's idle timeout, while
         * still reusing warm sockets on the hot path (agent-loop turns fire
         * back-to-back within the threshold and skip eviction). Especially
         * covers the FIRST post-compaction / post-pause turn.
         *
         * @return true if the pool was evicted.
         */
        fun evictLLMConnectionsIfIdle(
            idleThresholdMs: Long = com.openminis.app.data.StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS,
        ): Boolean {
            val last = lastLLMActivityAtMs
            if (last == 0L) return false
            val idleMs = android.os.SystemClock.elapsedRealtime() - last
            if (!com.openminis.app.data.StaleConnectionPolicy
                    .shouldEvictBeforeRequest(idleMs, idleThresholdMs)
            ) {
                return false
            }
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
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

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
                _status.value = NetworkStatus.CONNECTED
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
                _status.value = NetworkStatus.DISCONNECTED
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
                    _status.value = newStatus
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
