package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-auto-mode] App-level persisted Auto Mode toggle (FastModePrefs pattern).
 * Gates the arming phrases: with the toggle off, "авто-режим" in a user
 * message is plain text; with it on, that message arms the autonomous loop.
 * Default OFF — the user enables it deliberately (their explicit contract).
 *
 * primed in MinisApp.onCreate, context-free reads afterwards.
 */
object AutoModePrefs {
    private const val PREFS = "minis_auto_mode_prefs"
    private const val KEY_ENABLED = "autoModeEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    private val _enabledFlow = MutableStateFlow(false)

    /**
     * [T-auto-mode-quick-toggle] Observable state — the in-chat composer
     * button and the Background-settings switch read the SAME flow, so
     * flipping either recomposes the other. Until [prime] it is false,
     * matching a fresh install.
     */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
        _enabledFlow.value = cachedEnabled
    }

    /** Context-free read. False before [prime] — matches a fresh install. */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        _enabledFlow.value = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
