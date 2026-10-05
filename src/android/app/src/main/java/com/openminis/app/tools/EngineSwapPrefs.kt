package com.openminis.app.tools

import android.content.Context
import android.content.SharedPreferences

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
 */
object EngineSwapPrefs {
    private const val PREFS = "minis_debug_engine_swap"
    private const val KEY = "enabled"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun isEnabled(): Boolean {
        val p = prefs ?: return false
        return p.getBoolean(KEY, false)
    }

    fun setEnabled(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
    }
}
