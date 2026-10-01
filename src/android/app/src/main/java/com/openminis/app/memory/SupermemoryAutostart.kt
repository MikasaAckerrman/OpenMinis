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
 * Boot: on MinisApp.onCreate AND on every foreground return
 * ([T-supermemory-fg-heal] — a backgrounded app whose server was killed
 * by the LMK stays dead until the next full process start without it; the
 * foreground call makes the heal as frequent as the user's returns). A
 * background coroutine probes the local port
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
    // [T-fg-boot-niced] The server boot is 10-26s of CPU. Un-niced, it
    // competes with the UI threads exactly when the user returns to the
    // app (the measured "выхожу в фон и захожу — очень сильно пролагивает").
    // nice -n 10: the Node process still boots, but the UI/renderer cores
    // always win scheduling — the boot may stretch slightly, the frames
    // never pay for it.
    private const val BOOT_CMD =
        "nohup nice -n 10 sh /var/minis/shared/supermemory/run.sh >/tmp/sm_autostart.log 2>&1 & echo boot_kick"
    private const val STOP_CMD =
        "sh /var/minis/shared/supermemory/run.sh stop >/tmp/sm_stop.log 2>&1; echo stop_kick"
    private const val SYSTEM_SESSION = "system"

    /** [T-supermemory-stop-grace] See stopIfNeeded. */
    private const val STOP_GRACE_MS = 30_000L

    /**
     * [T-fg-boot-lag] Delay before a FOREGROUND-triggered boot check.
     * The resume window (activity relayout, chat recomposition, stream
     * reconnection) owns the first ~4s of CPU; the server is not needed
     * until the user actually SENDS (the bridge fails fast via the
     * breaker + 600ms budget when the server is down). 0 for onCreate.
     */
    private const val FG_BOOT_DELAY_MS = 4_000L

    /**
     * [T-supermemory-fg-heal] At most one boot kick IN FLIGHT — the doc's
     * old "at most one boot per process" claim had no guard at all, and the
     * new foreground-heal call site makes concurrent kicks a real shape:
     * two run.sh invocations would interleave their pkill/sleep/start
     * sequences, the second one killing the first's freshly-started server
     * (the stale-lock fix's live-pid branch does exactly that). CAS-guarded:
     * a second caller while a kick is in flight (including the 30s probe
     * loop) skips; after failure the flag resets, so each foreground return
     * is a fresh retry opportunity.
     */
    private val bootKickInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Call from MinisApp.onCreate AND on foreground returns. Non-blocking.
     *  [T-fg-boot-lag] delayMs: pass FG_BOOT_DELAY_MS from the foreground
     *  call site so the boot work starts after the resume window settles;
     *  0 (default) for onCreate. */
    fun bootIfNeeded(
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        delayMs: Long = 0L,
    ) {        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (portOpen()) {
                AppLogger.info(TAG, "server already up — no boot needed")
                return@launch
            }
            if (!bootKickInFlight.compareAndSet(false, true)) {
                AppLogger.info(TAG, "boot kick already in flight — skipping")
                return@launch
            }
            try {
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
                        // [T-supermemory-autostart] The boot window may have
                        // already logged breaker failures (the user's first
                        // message racing the boot) — reset it so the healthy
                        // server is not fast-skipped for the breaker's full
                        // 10-minute window.
                        SupermemoryBridge.onServerUp()
                        return@launch
                    }
                }
                AppLogger.warning(TAG, "server not up after 30s — check /tmp/sm_autostart.log in the sandbox")
            } finally {
                bootKickInFlight.set(false)
            }
        }
    }

    /**
     * [T-supermemory-bg-stop] FPS guard (user 28.09: «не сделает хуже фпс»):
     * while the app sits backgrounded — the user gaming, typically — the
     * detached stack still holds ~245MB RSS and a few % CPU of pure
     * contention. The lifecycle is now SYMMETRIC: boot on every foreground
     * return (the existing fg-heal above), STOP on the background
     * transition when no agent session is running. The call site (MinisApp
     * onActivityStopped, foregroundActivityCount==0) guards the
     * active-session case — a backgrounded FGS turn still distills into
     * the server at turn end. A down-server window is covered the same way
     * it always was: the compact path enqueues to pending/ (drained on the
     * next boot), distiller/recall fail fast through the bridge breaker.
     * Scheduled background runs are the residual trade-off — their turn
     * distillates miss the server until the user next foregrounds Minis.
     * Non-blocking, idempotent (port down → no-op).
     */
    fun stopIfNeeded(
        /** [T-supermemory-stop-grace] Live foreground probe — passed as a
         *  lambda because the state lives on the MinisApp instance. */
        isForeground: () -> Boolean,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ) {
        scope.launch {
            // [T-supermemory-stop-grace] A 30s grace before the stop: rapid
            // app switching (messenger <-> Minis, 1-5s hops) used to pay a
            // full server boot (~10s CPU, ~245MB RSS re-fault) on EVERY
            // return, because the stop is immediate and the fg-heal boots
            // unconditionally. Measured on device (01.10, 07:17: bg → stop
            // 0.1s → fg → boot 10s). A real background (gaming, phone idle)
            // is still stopped — 30s later; the FPS guard's goal is long
            // background sessions, not sub-minute switches.
            delay(STOP_GRACE_MS)
            if (isForeground()) {
                AppLogger.info(TAG, "still foreground after grace — skip stop (rapid switch)")
                return@launch
            }
            // [T-supermemory-stop-grace] A session may have STARTED during
            // the grace (backgrounded FGS turn) — it needs the server for
            // turn-end distillation. The call-site guard saw an empty
            // tracker 30s ago; re-check now.
            if (com.openminis.app.service.SessionActivityTracker
                    .activeSessions.value.isNotEmpty()
            ) {
                AppLogger.info(TAG, "active session appeared during grace — skip stop")
                return@launch
            }
            if (!portOpen()) return@launch
            AppLogger.info(TAG, "app backgrounded — stopping the supermemory stack (FPS guard)")
            runCatching {
                ExecutionCoordinator.execute(
                    SYSTEM_SESSION,
                    STOP_CMD,
                    timeout = 15_000L,
                )
            }.onSuccess { res ->
                AppLogger.info(TAG, "stop exit=${res.exitCode}")
            }.onFailure { e ->
                AppLogger.warning(TAG, "stop failed: ${e.message}")
            }
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
