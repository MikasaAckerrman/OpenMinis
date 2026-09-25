package com.openminis.app.memory

import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * [T-supermemory-autostart] The supermemory server's lifecycle MATCHES the
 * app's, not the phone's (user decision 25.09: «он разве не должен
 * запускаться когда запускаю minis, а не когда я включаю телефон? Смысл его
 * включать при перезагрузке, если я использую его только в minis»).
 *
 * Boot: on MinisApp.onCreate, a background coroutine probes the local port
 * and, when dead, kicks `run.sh` through the persistent PRoot shell —
 * [ExecutionCoordinator] auto-boots the sandbox, injects the environment
 * (the API keys run.sh extracts) and mounts the shared dir the script
 * lives in. The detached server is a child of the app's process tree:
 * when Minis dies, the whole sandbox tree dies with it — no orphans
 * between app runs, no boot-time waste on a phone reboot that might never
 * open Minis.
 *
 * While the server is down, every consumer already degrades honestly:
 * the compact path enqueues knowledge to pending/ (drained by run.sh on
 * the next boot), the distiller and recall fail fast through the bridge's
 * circuit breaker.
 */
object SupermemoryAutostart {
    private const val TAG = "Minis.SupermemoryAuto"
    private const val PORT = 6767
    private const val BOOT_CMD =
        "nohup sh /var/minis/shared/supermemory/run.sh >/tmp/sm_autostart.log 2>&1 & echo boot_kick"
    private const val SYSTEM_SESSION = "system"

    /** Call from MinisApp.onCreate. Non-blocking, at most one boot per process. */
    fun bootIfNeeded(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            if (portOpen()) {
                AppLogger.info(TAG, "server already up — no boot needed")
                return@launch
            }
            AppLogger.info(TAG, "port $PORT down — kicking run.sh via the persistent shell")
            runCatching {
                ExecutionCoordinator.execute(
                    SYSTEM_SESSION,
                    BOOT_CMD,
                    timeout = 30_000L,
                )
            }.onSuccess { res ->
                AppLogger.info(TAG, "boot kick exit=${res.exitCode} (detached; server boots ~9s)")
            }.onFailure { e ->
                AppLogger.warning(TAG, "boot kick failed: ${e.message}")
                return@launch
            }
            // Wait for the server to accept connections (boot ~9s; pending
            // drain may add a few more). Bounded — the circuit breaker
            // covers any late start.
            for (i in 1..15) {
                delay(2_000)
                if (portOpen()) {
                    AppLogger.info(TAG, "server is UP after ${i * 2}s")
                    return@launch
                }
            }
            AppLogger.warning(TAG, "server not up after 30s — check /tmp/sm_autostart.log in the sandbox")
        }
    }

    /** True when something accepts TCP on the server port (shared netns). */
    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 700) }
        true
    } catch (_: Exception) {
        false
    }
}
