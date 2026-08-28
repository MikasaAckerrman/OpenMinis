package com.openminis.app.ui

/**
 * [T-android-screen-dim] Pure decision logic for the black idle overlay.
 *
 * Extracted from the Compose layer so the rules are unit-testable without an
 * Android view tree. The overlay is a battery/burn-in mitigation for the
 * keep-screen-awake path: the window must stay technically ON (that is what
 * keeps the radio from parking on aggressive OEM ROMs), so instead of letting
 * the panel stay lit we paint pure black — on AMOLED those pixels are simply
 * off.
 */
object ScreenDimPolicy {

    /**
     * Whether the black overlay should currently cover the screen.
     *
     * All four conditions must hold:
     *  - the keep-awake toggle is on (otherwise the system sleeps the screen
     *    normally and an overlay would just be a black screen before sleep);
     *  - a delay is configured ([delaySec] > 0 means the feature is enabled);
     *  - a task is actually running (dimming an idle app would hide the UI the
     *    user is looking at);
     *  - the user has not touched the screen for at least [delaySec].
     *
     * @param keepAwakeEnabled Appearance → Keep Screen Awake.
     * @param delaySec configured idle delay; 0 = feature off.
     * @param hasActiveTask true while at least one session is running.
     * @param idleMs milliseconds since the last touch.
     */
    fun shouldDim(
        keepAwakeEnabled: Boolean,
        delaySec: Int,
        hasActiveTask: Boolean,
        idleMs: Long,
    ): Boolean {
        if (!keepAwakeEnabled) return false
        if (delaySec <= 0) return false
        if (!hasActiveTask) return false
        return idleMs >= delaySec * 1000L
    }

    /**
     * Window brightness to request while dimmed.
     *
     * 0f (not [android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF],
     * which is -1f and means "restore system default") drives the backlight to
     * its minimum. On AMOLED the black pixels are already unlit; this kills the
     * remaining panel glow without turning the screen OFF, which would let the
     * radio park again.
     */
    const val DIMMED_BRIGHTNESS = 0f

    /** Sentinel meaning "hand brightness control back to the system". */
    const val RESTORE_BRIGHTNESS = -1f
}
