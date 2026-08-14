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

    // ── [T-haptics-customization] Buzz shape ───────────────────────────────
    //
    // Three independent axes + a DND override, each persisted under its own key
    // so adding a pattern later can't invalidate a stored intensity. Read
    // straight from prefs for the same reason isCompletionVibrationEnabled
    // does: minis-config writes these keys directly, so a cached copy can be one
    // write behind.

    private val _vibrationProfile = MutableStateFlow(readVibrationProfile())
    val vibrationProfile: StateFlow<com.openminis.app.feedback.VibrationProfile> =
        _vibrationProfile.asStateFlow()

    fun readVibrationProfile(): com.openminis.app.feedback.VibrationProfile =
        com.openminis.app.feedback.VibrationProfile(
            pattern = com.openminis.app.feedback.VibrationPattern
                .fromId(prefs.getString(KEY_VIBRATION_PATTERN, null)),
            intensity = com.openminis.app.feedback.VibrationIntensity
                .fromId(prefs.getString(KEY_VIBRATION_INTENSITY, null)),
            length = com.openminis.app.feedback.VibrationLength
                .fromId(prefs.getString(KEY_VIBRATION_LENGTH, null)),
            bypassDnd = prefs.getBoolean(KEY_VIBRATION_BYPASS_DND, false),
        )

    fun setVibrationPattern(p: com.openminis.app.feedback.VibrationPattern) {
        prefs.edit().putString(KEY_VIBRATION_PATTERN, p.id).apply()
        _vibrationProfile.value = readVibrationProfile()
    }

    fun setVibrationIntensity(i: com.openminis.app.feedback.VibrationIntensity) {
        prefs.edit().putString(KEY_VIBRATION_INTENSITY, i.id).apply()
        _vibrationProfile.value = readVibrationProfile()
    }

    fun setVibrationLength(l: com.openminis.app.feedback.VibrationLength) {
        prefs.edit().putString(KEY_VIBRATION_LENGTH, l.id).apply()
        _vibrationProfile.value = readVibrationProfile()
    }

    fun setVibrationBypassDnd(value: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_BYPASS_DND, value).apply()
        _vibrationProfile.value = readVibrationProfile()
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
            when (key) {
                KEY_COMPLETION_VIBRATION ->
                    _completionVibrationEnabled.value = sp.getBoolean(KEY_COMPLETION_VIBRATION, false)
                KEY_VIBRATION_PATTERN, KEY_VIBRATION_INTENSITY,
                KEY_VIBRATION_LENGTH, KEY_VIBRATION_BYPASS_DND ->
                    _vibrationProfile.value = readVibrationProfile()
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
    /**
     * [T-overlay-portrait-offscreen] Stored PER ORIENTATION. One shared slot
     * meant a drag in landscape overwrote the portrait position with an x that
     * portrait cannot contain; combined with FLAG_LAYOUT_NO_LIMITS (which
     * honours an off-screen x literally) the capsule was placed past the right
     * edge and never seen — the overlay looked broken in portrait while working
     * in landscape. Keys are new rather than reused so a value written by an
     * older build falls back to "no remembered position" once and is re-learned
     * per orientation.
     */
    fun getOverlayX(landscape: Boolean): Int =
        prefs.getInt(if (landscape) KEY_BG_OVERLAY_X_LAND else KEY_BG_OVERLAY_X_PORT, -1)

    fun getOverlayY(landscape: Boolean): Int =
        prefs.getInt(if (landscape) KEY_BG_OVERLAY_Y_LAND else KEY_BG_OVERLAY_Y_PORT, -1)

    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean) {
        prefs.edit()
            .putInt(if (landscape) KEY_BG_OVERLAY_X_LAND else KEY_BG_OVERLAY_X_PORT, x)
            .putInt(if (landscape) KEY_BG_OVERLAY_Y_LAND else KEY_BG_OVERLAY_Y_PORT, y)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        private const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        // [T-overlay-portrait-offscreen] Per-orientation position slots. The old
        // shared keys are deliberately left unread: a value saved by a previous
        // build may be a landscape x, and inheriting it into portrait recreates
        // the very bug this splits apart.
        private const val KEY_BG_OVERLAY_X_PORT = "backgroundOverlayXPortrait"
        private const val KEY_BG_OVERLAY_Y_PORT = "backgroundOverlayYPortrait"
        private const val KEY_BG_OVERLAY_X_LAND = "backgroundOverlayXLandscape"
        private const val KEY_BG_OVERLAY_Y_LAND = "backgroundOverlayYLandscape"
        private const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"
        // [T-completion-haptics] Shared with minis-config's
        // `background.completionVibration` field — same prefs file + key, so a
        // CLI write and a UI toggle are the same write.
        private const val KEY_COMPLETION_VIBRATION = "completionVibrationEnabled"
        // [T-haptics-customization] Buzz shape, one key per axis.
        private const val KEY_VIBRATION_PATTERN = "completionVibrationPattern"
        private const val KEY_VIBRATION_INTENSITY = "completionVibrationIntensity"
        private const val KEY_VIBRATION_LENGTH = "completionVibrationLength"
        private const val KEY_VIBRATION_BYPASS_DND = "completionVibrationBypassDnd"
    }
}
