package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-root-shell] App-level persisted toggle for the root_shell tool: the
 * model may execute Android-side commands with KERNEL ROOT (KernelSU/
 * ReSukiSU `su 0`) when — and only when — the user has armed this gate.
 *
 * Default OFF. This is the most privileged surface the app can offer: a
 * root command can read/write ANY file on the device, kill any process,
 * reconfigure the system. The tool additionally routes every command
 * through [com.openminis.app.sandbox.DestructiveCommandGate] — a
 * destructive root command still needs the interactive approval dialog
 * — and the gate here is the master switch (schema-level: OFF → the tool
 * does not even exist for the model).
 *
 * Root availability is checked at RUN time, not here: `su 0 id` failing
 * (KSU not started after a reboot etc.) produces a clean tool error with
 * the recovery hint (run root.sh), never a crash.
 */
object RootShellPrefs {
    private const val PREFS = "minis_root_shell_prefs"
    private const val KEY_ENABLED = "rootShellEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private val _enabledFlow = MutableStateFlow(false)

    /** Observable state for the settings switch. */
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
