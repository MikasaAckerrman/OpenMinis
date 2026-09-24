package com.openminis.app.service

import android.app.Application
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * [T-background-survival] System-level exemptions that keep Minis actually
 * WORKING when the app is backgrounded or the screen is off.
 *
 * The user's report: "ты в фоне вообще не работаешь, даже с выданными
 * разрешениями" — UI-granted permissions (battery unrestricted etc.) do NOT
 * stop the three real killers:
 *
 *  1. **Phantom process monitor** (Android 12+): kills CHILD processes of a
 *     backgrounded app — the PRoot sandbox and every shell tool are children.
 *     One `settings put global settings_enable_monitor_phantom_proc false`
 *     disables it globally; verified live on this device (24.09).
 *  2. **Doze**: defers jobs/network for unlisted apps even with an FGS.
 *     `dumpsys deviceidle whitelist +<pkg>` exempts us.
 *  3. **App Standby bucket**: a "rare"/"restricted" bucket throttles the app
 *     between uses. Pinning to `active` removes that.
 *
 * All three run fine from the Shizuku shell (uid 2000) — no root needed.
 * The app-side foreground service + partial wake lock (already in
 * [AgentForegroundService]) covers the CPU side; these exemptions cover the
 * "process frozen / children killed" side.
 *
 * Idempotent and self-healing: applied on every Shizuku READY transition
 * (app start or user starting Shizuku later); OEM resets of these settings
 * are corrected on the next launch. User can disable the whole thing in
 * Settings (background exemption toggle) — e.g. to satisfy a strict
 * battery-life policy.
 */
object BackgroundExemptionPolicy {

    private const val TAG = "BgExemptPolicy"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAppliedState: ShizukuManager.State? = null

    /**
     * Wire at Application init (after [ShizukuManager.init]). Collects the
     * Shizuku state and applies the exemptions on the not-ready → READY
     * transition. No-op forever when Shizuku is absent — honest degradation,
     * no dialogs, no crash.
     */
    fun init(
        app: Application,
        isEnabled: () -> Boolean,
    ) {
        scope.launch {
            ShizukuManager.snapshot.collect { snap ->
                val wasReady = lastAppliedState == ShizukuManager.State.READY
                lastAppliedState = snap.state
                if (snap.state == ShizukuManager.State.READY && !wasReady && isEnabled()) {
                    apply(app.packageName)
                }
            }
        }
    }

    /** Run the exemption commands. Idempotent; every failure is logged, none is fatal. */
    fun apply(pkg: String) {
        val cmds = listOf(
            listOf("settings", "put", "global", "settings_enable_monitor_phantom_proc", "false"),
            listOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            listOf("am", "set-standby-bucket", pkg, "active"),
        )
        var ok = 0
        for (cmd in cmds) {
            val res = runCatching {
                ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 8_000)
            }.getOrNull()
            if (res != null && res.exitCode == 0) {
                ok++
            } else {
                AppLogger.warning(
                    TAG,
                    "exemption failed: ${cmd.joinToString(" ")} → " +
                        "exit=${res?.exitCode ?: "n/a"} ${(res?.stderr ?: "").take(80)}",
                )
            }
        }
        AppLogger.info(TAG, "background exemptions applied: $ok/${cmds.size} (pkg=$pkg)")
    }

    /** Called from process teardown paths that exist for tests. */
    fun shutdown() {
        scope.cancel()
    }
}
