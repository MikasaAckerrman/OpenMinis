package com.openminis.app.tools

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * [T-m12-engine-swap] The strangler switch for the agent-loop migration
 * (M6–M12): when enabled, the streaming send/resume path routes through
 * [com.openminis.app.engine.EngineAgentLoop] + the production adapters
 * (ProviderModelGateway, ChatTurnReducer) instead of the legacy
 * in-ViewModel loop.
 *
 * OFF by default. The flip is deliberate: the engine chain is
 * unit-verified headlessly, but the legacy loop carries eight production
 * behaviours (T94 throttling, queued-message interrupts, partial-turn
 * durability, fallback chain, auto-resume, compaction triggers) that only
 * a live device run can prove end-to-end. This flag turns the swap into
 * an observable, reversible experiment:
 *
 *   debug.engineSwap {"enabled":true}   — RPC, immediate
 *
 * Once a live week passes on the engine path, the default flips here and
 * the legacy loop retires in the same series.
 *
 * [T-swap-flag-cross-build] A debug-RPC experiment must NOT survive an app
 * update. SharedPreferences persist across reinstalls of the same package
 * — a stale enabled=true (flipped on vc98 while the engine path still had
 * the user-text annihilation bug) silently routed the vc99/vc100 installs
 * through the broken chain with nobody asking for it, and looked exactly
 * like "the new build regressed". The flag is now stamped with the
 * versionCode it was armed on; init() resets it whenever the app version
 * changed. Re-arming on a new build is one RPC — deliberate, visible in
 * the audit log, never inherited.
 */
object EngineSwapPrefs {
    private const val PREFS = "minis_debug_engine_swap"
    private const val KEY = "enabled"
    private const val KEY_ARMED_AT_VERSION = "armedAtVersionCode"
    // [T-engine-shadow-sessions] Per-session routing set for shadow
    // testing (user's spec, 08.10: "пустая сессия на новом движке, я
    // старым мониторю её ошибки"). A session in this set routes through
    // the engine chain even while the GLOBAL flag stays OFF — one
    // conversation on the new engine, everything else on the proven
    // legacy loop. Same cross-build hygiene as the flag: the set is
    // cleared on version change.
    private const val KEY_SESSION_OVERRIDES = "engine_session_overrides"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        val current = currentVersionCode(context)
        val stored = prefs?.getInt(KEY_ARMED_AT_VERSION, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (mustResetOnUpgrade(stored, current)) {
            // Cross-build (or first run): the experiment is not armed for
            // THIS build. Reset to the default (OFF) and stamp the build.
            // Shadow sessions die with the build too — they are armed
            // per-experiment, never inherited across installs.
            prefs?.edit()
                ?.putBoolean(KEY, false)
                ?.putInt(KEY_ARMED_AT_VERSION, current)
                ?.putStringSet(KEY_SESSION_OVERRIDES, null)
                ?.apply()
        }
    }

    fun isEnabled(): Boolean {
        val p = prefs ?: return false
        return p.getBoolean(KEY, false)
    }

    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }

    /**
     * [T-engine-shadow-sessions] Engine routing for ONE session: the
     * per-session override wins when set, otherwise the global flag.
     * Called from the send / resume / queue-drain swap sites with the
     * live session id.
     */
    fun isEnabledFor(sessionId: String?): Boolean {
        if (sessionId != null && isSessionOverridden(sessionId)) return true
        return isEnabled()
    }

    fun isSessionOverridden(sessionId: String): Boolean {
        val p = prefs ?: return false
        if (sessionId.isBlank()) return false
        return p.getStringSet(KEY_SESSION_OVERRIDES, emptySet())
            ?.contains(sessionId) == true
    }

    fun setSessionOverride(sessionId: String, enabled: Boolean) {
        if (sessionId.isBlank()) return
        val current = prefs?.getStringSet(KEY_SESSION_OVERRIDES, emptySet()) ?: emptySet()
        val next = if (enabled) current + sessionId else current - sessionId
        prefs?.edit()?.putStringSet(KEY_SESSION_OVERRIDES, next)?.apply()
    }

    /** For diagnostics: the armed shadow-session set (read-only copy). */
    fun sessionOverrides(): Set<String> =
        prefs?.getStringSet(KEY_SESSION_OVERRIDES, emptySet())?.toSet() ?: emptySet()

    /**
     * Pure decision for unit tests: reset unless the flag was armed on
     * exactly this build. Int.MIN_VALUE = never stamped (pre-stamp install
     * or fresh prefs) → reset (the pre-stamp builds carried the vc98-era
     * landmine this guard exists to defuse).
     */
    fun mustResetOnUpgrade(storedArmedAt: Int, currentVersionCode: Int): Boolean =
        storedArmedAt != currentVersionCode

    private fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        Int.MIN_VALUE
    }
}
