package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Master switch for automatic context upkeep, persisted in SharedPreferences.
 * Registered in the config bridge as `context.autoCompact`, so the CLI/agent
 * and any future Settings switch share one source of truth.
 *
 * When enabled (default), every user send runs [ContextMaintenance.decide] and
 * performs the tier it selects — a free local pass every turn, a model-assisted
 * summarisation pass on cadence + pressure, or on-device compression once the
 * window is too full for a summarisation request to be reliable. When disabled,
 * nothing happens automatically and the user drives `/compact` and `/rescue`
 * by hand.
 *
 * [T-context-maintenance] This replaced an earlier single-threshold trigger
 * (fire one compact when ContextPolicy said NEEDS_COMPACT). That fired too
 * late to help a session filling in big tool-result jumps, and did nothing at
 * all in between — hence the tiered policy in [ContextMaintenance].
 */
object ContextAutoCompactPrefs {
    private const val PREFS = "minis_context_prefs"
    private const val KEY_AUTO_COMPACT = "context.autocompact.enabled"

    /**
     * [T-manual-model-compaction] Separate switch for the MODEL-ASSISTED
     * summarisation pass (FULL/RESCUE tiers). Split out from [KEY_AUTO_COMPACT]
     * because those two tiers have very different risk profiles:
     *
     *  - The LIGHT pass (offload oversized tool payloads to disk) is local,
     *    free, and cannot fail a request — it stays automatic under
     *    [KEY_AUTO_COMPACT].
     *  - The FULL/RESCUE pass fires a real request whose body is derived from
     *    the very history that is already large; providers were content-
     *    filtering that request and it was silently rewriting session history
     *    in ways the user did not ask for. It now defaults OFF: the user drives
     *    summarisation by hand with `/compact`, and only opts into the
     *    automatic model pass explicitly.
     */
    private const val KEY_MODEL_PASS = "context.autocompact.modelpass.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Master switch for the free, local upkeep (LIGHT offload). Default ON —
     * offloading big tool results to disk keeps a tool-heavy session from
     * spiking into the wall and cannot fail the turn, so there is no reason to
     * ration it.
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_COMPACT, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COMPACT, enabled).apply()
    }

    /**
     * Whether the model-assisted summarisation pass (FULL/RESCUE) may run
     * automatically. Default OFF — the user runs `/compact` by hand. When this
     * is off, the loop still performs the free LIGHT offload every turn.
     */
    fun isModelPassEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MODEL_PASS, false)

    fun setModelPassEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MODEL_PASS, enabled).apply()
    }
}
