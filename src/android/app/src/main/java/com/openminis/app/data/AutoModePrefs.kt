package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

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

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    /** Context-free read. False before [prime] — matches a fresh install. */
    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
