package com.openminis.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-screen-dim] Singleton tracking whether the screen-dim overlay
 * is currently active. Mirrors ScreenInteractionTracker's pattern.
 *
 * Used by DeletionGuardMonitor (and potentially other dialog hosts) to skip
 * showing popups while the dim overlay covers the screen — a dialog rendered
 * on top of a black background looks broken and the user can't see context.
 */
object ScreenDimState {

    private val _isDimmed = MutableStateFlow(false)

    /** True when the full-screen black dim overlay is covering the UI. */
    val isDimmed: StateFlow<Boolean> = _isDimmed.asStateFlow()

    /** Called by ScreenDimOverlay when it enters/leaves the dimmed state. */
    fun setDimmed(dimmed: Boolean) {
        _isDimmed.value = dimmed
    }
}
