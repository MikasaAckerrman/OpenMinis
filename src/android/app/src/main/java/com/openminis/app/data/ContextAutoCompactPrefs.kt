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

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_COMPACT, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COMPACT, enabled).apply()
    }
}
