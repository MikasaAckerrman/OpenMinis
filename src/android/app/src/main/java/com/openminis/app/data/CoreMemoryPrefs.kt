package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-letta-core-memory] App-level persisted toggle for the core-memory layer
 * (Letta blocks): gates BOTH surfaces —
 *  1. the effectiveAgentHistory injection (4th layer, wire-only), and
 *  2. the memory_blocks_view / memory_blocks_edit tool schema entries.
 * With the toggle off the model can't even attempt a block edit, and the
 * request payload carries no core-memory header — zero cost, exactly the
 * [T-subagent-gate] discipline.
 *
 * User decision 24.09 (plan): the gate lives in Settings (the Memory
 * management screen), NOT in the chat "..." menu — unlike auto-mode /
 * subagents this is an infrastructure feature, not a per-conversation knob.
 * Default OFF; the user enables it deliberately.
 *
 * FastModePrefs/AutoModePrefs/SubagentPrefs pattern: primed in
 * MinisApp.onCreate, context-free reads afterwards, [enabledFlow] so the
 * settings switch recomposes.
 */
object CoreMemoryPrefs {
    private const val PREFS = "minis_core_memory_prefs"
    private const val KEY_ENABLED = "coreMemoryEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private val _enabledFlow = MutableStateFlow(false)

    /** Observable state for the Memory management settings switch. */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
        _enabledFlow.value = cachedEnabled
    }

    /** Context-free read. False before [prime] — the safe default. */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        _enabledFlow.value = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
