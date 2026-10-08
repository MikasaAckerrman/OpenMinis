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
 * ON by default (flipped 08.10 by the user's decision after the shadow
 * campaign: the engine chain proved itself live — tool turns, multi-round
 * turns, memory recall, multimodal, dead-turn degradation, 55-minute
 * 107-tool stress — while every failure found was in the routing AROUND
 * it, all fixed). The legacy in-ViewModel loop stays compiled as the
 * BACKUP: per-turn degradation (engine failure → legacy automatically)
 * plus a manual escape hatch — the engine button in the chat top bar
 * (green bolt = new engine leads, amber shield = legacy leads).
 *
 *   debug.engineSwap {"enabled":false}  — RPC, immediate (legacy)
 *
 * [T-swap-flag-cross-build] A debug-RPC experiment must NOT survive an app
 * update. SharedPreferences persist across reinstalls of the same package
 * — a stale enabled=true (flipped on vc98 while the engine path still had
 * the user-text annihilation bug) silently routed the vc99/vc100 installs
 * through the broken chain with nobody asking for it, and looked exactly
 * like "the new build regressed". The flag is now stamped with the
 * versionCode it was armed on; init() resets it whenever the app version
 * changed — to the DEFAULT (engine ON). The user's manual legacy choice
 * is per-build too: an update re-arms the default, the button re-applies
 * the choice in one tap.
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
            // Cross-build (or first run): reset to the default (ENGINE ON —
            // the new engine leads since 08.10) and stamp the build. The
            // user's legacy choice is one button tap away, never inherited.
            prefs?.edit()
                ?.putBoolean(KEY, true)
                ?.putInt(KEY_ARMED_AT_VERSION, current)
                ?.putStringSet(KEY_SESSION_OVERRIDES, null)
                ?.apply()
        }
    }

    fun isEnabled(): Boolean {
        val p = prefs ?: return true
        return p.getBoolean(KEY, true)
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
