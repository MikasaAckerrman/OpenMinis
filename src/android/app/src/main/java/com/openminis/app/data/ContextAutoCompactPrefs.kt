package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Global toggle for automatic context compaction, persisted in
 * SharedPreferences. Registered in the config bridge as
 * `context.autoCompact` (see ConfigBuiltins.registerContext), so the
 * CLI/agent and any future Settings switch share one source of truth.
 *
 * When enabled (default), crossing [ContextPolicy.compactThreshold] at
 * send time kicks off a background `compactAll()` instead of only
 * appending a "consider /compact" notice that most users never act on.
 * The policy thresholds deliberately leave 10-20K tokens of headroom, so
 * the in-flight turn still fits while the summary is being built; the
 * NEXT turn starts on the compacted history.
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

/**
 * Pure decision for "should this send kick off an auto-compact?".
 * Kept Android-free so the matrix is unit-testable on the JVM.
 *
 * Guards:
 *   - only on [ContextPolicy.CheckResult.NEEDS_COMPACT] — EXHAUSTED tiers
 *     are small windows where compact is unavailable by design;
 *   - never stack on an in-flight compact;
 *   - cooldown so a summary that didn't shrink enough doesn't retrigger
 *     a compact on every single send.
 */
object ContextAutoCompact {
    const val COOLDOWN_MS = 10 * 60 * 1000L

    fun shouldTrigger(
        check: ContextPolicy.CheckResult,
        enabled: Boolean,
        isCompacting: Boolean,
        lastRunAtMs: Long,
        nowMs: Long,
    ): Boolean =
        enabled &&
            check == ContextPolicy.CheckResult.NEEDS_COMPACT &&
            !isCompacting &&
            (nowMs - lastRunAtMs) >= COOLDOWN_MS
}
