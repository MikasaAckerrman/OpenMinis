package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-subagent-gate] App-level persisted toggle for the subagent machinery
 * (spawn_subagent / spawn_many / run_graph / list_agents). User decision
 * 23.09.2026: subagents are OPT-IN — default OFF, the user enables them
 * deliberately. Gating the TOOL SCHEMA (not the prompt): with the toggle
 * off the model can't even attempt a spawn call; with it on, the usual
 * contract applies (spawn only when a subtask would flood the main
 * conversation or parallelism is genuinely needed).
 *
 * FastModePrefs/AutoModePrefs pattern: primed in MinisApp.onCreate,
 * context-free reads afterwards, [enabledFlow] so UI toggles recompose.
 */
object SubagentPrefs {
    private const val PREFS = "minis_subagent_prefs"
    private const val KEY_ENABLED = "subagentsEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private val _enabledFlow = MutableStateFlow(false)

    /** Observable state for the settings switch / future in-chat button. */
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
