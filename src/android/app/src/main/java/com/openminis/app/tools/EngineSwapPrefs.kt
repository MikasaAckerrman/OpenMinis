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
 * Default: ON ([B29-engine-swap-default], 06.11). History: flipped ON
 * 08.10 after the shadow campaign; reverted to OFF 09.10 ("the engine
 * earns its default on the stand") after the second-system mirror seams;
 * ON again 06.11 once the blocker list was closed — the gateway Usage
 * seam bridged ([B29-engine-usage]: engine turns no longer persist
 * token_usage = NULL) and the CPU/IO concurrency gates ([P1]) removed
 * the parallelism bottleneck. The legacy in-ViewModel loop stays
 * compiled as the BACKUP: per-turn degradation (engine failure → legacy
 * automatically) plus a manual escape hatch — the engine button in the
 * chat top bar (green bolt = new engine leads, amber shield = legacy
 * leads).
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
            // Cross-build (or first run): reset to the CURRENT default —
            // the engine leads ([B29-engine-swap-default], 06.11). The
            // vc98/vc120 landmine this guard exists for stays defused: no
            // stale flag survives an upgrade in EITHER direction, and the
            // user's manual legacy choice is re-applied by the toggle in
            // one tap after the update.
            prefs?.edit()
                ?.putBoolean(KEY, true)
                ?.putInt(KEY_ARMED_AT_VERSION, current)
                ?.putStringSet(KEY_SESSION_OVERRIDES, null)
                ?.apply()
        }
    }

    fun isEnabled(): Boolean {
        // [B29-engine-swap-default] Default ON since 06.11 (reviewer+user
        // decision): the blocker list that kept the engine opt-in is
        // closed — Usage bridged ([B29-engine-usage]), CPU/IO gates ([P1]),
        // and every engine failure degrades to legacy PER TURN (the
        // send-path catch), so a bad turn costs one turn, not a session.
        // A missing key or a wiped store lands on the ENGINE. The
        // null-prefs pre-init state stays conservative (legacy): init()
        // runs in MinisApp before any send can happen, so this branch is
        // a broken-setup guard only.
        val p = prefs ?: return false
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
