package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * [T-android-dynamic-island] "Show live status on the dynamic island"
     * toggle (Android 16 Live Updates). Defaults to OFF — the capability only
     * exists on Android 16+ with the per-app grant, and when ON it REPLACES the
     * floating overlay (mutual exclusion in AgentForegroundService.applyOverlayState),
     * so we don't want it silently changing behavior on upgrade. Reactive:
     * flipping it re-drives the FG service's combined flow so the overlay
     * appears/disappears without an app restart.
     */
    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    /**
     * [T-completion-haptics] "Double-buzz when a turn finishes" toggle.
     * Defaults to OFF: a vibration is a physical interruption, and silently
     * adding one on upgrade would be a surprise on every reply. The user opts
     * in from Settings → Background & notifications.
     */
    private val _completionVibrationEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_COMPLETION_VIBRATION, false))
    val completionVibrationEnabled: StateFlow<Boolean> =
        _completionVibrationEnabled.asStateFlow()

    /**
     * Live read straight from prefs, for the buzz decision itself.
     *
     * NOT `completionVibrationEnabled.value`: minis-config's
     * `background.completionVibration` writes the SharedPreferences key
     * directly (that's how every PrefsBoolField works), so the cached flow can
     * be one write behind after a CLI change. The Settings switch is kept in
     * sync separately by [prefsListener]; this method needs no cache at all —
     * it runs once per turn, and reading a boolean from an already-loaded prefs
     * map costs nothing.
     */
    fun isCompletionVibrationEnabled(): Boolean =
        prefs.getBoolean(KEY_COMPLETION_VIBRATION, false)

    fun setCompletionVibrationEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_COMPLETION_VIBRATION, value).apply()
        _completionVibrationEnabled.value = value
    }

    /**
     * Keeps [completionVibrationEnabled] honest when the key is written from
     * outside this class (minis-config). Held in a field because
     * SharedPreferences stores listeners weakly — a listener registered from a
     * local variable is collected at the next GC and silently stops firing.
     * The repository lives for the whole process, so there is nothing to
     * unregister.
     */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == KEY_COMPLETION_VIBRATION) {
                _completionVibrationEnabled.value = sp.getBoolean(KEY_COMPLETION_VIBRATION, false)
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag. -1 means "no remembered position — let the overlay
     * controller pick a default near the bottom-left, 10 dp from each
     * edge" ([T-bg-overlay-polish]).
     */
    fun getOverlayX(): Int = prefs.getInt(KEY_BG_OVERLAY_X, -1)
    fun getOverlayY(): Int = prefs.getInt(KEY_BG_OVERLAY_Y, -1)
    fun setOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BG_OVERLAY_X, x).putInt(KEY_BG_OVERLAY_Y, y).apply()
    }

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        private const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"
        // [T-completion-haptics] Shared with minis-config's
        // `background.completionVibration` field — same prefs file + key, so a
        // CLI write and a UI toggle are the same write.
        private const val KEY_COMPLETION_VIBRATION = "completionVibrationEnabled"
    }
}
