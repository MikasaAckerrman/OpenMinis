package com.openminis.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-screen-dim] Last time the user touched the screen.
 *
 * Fed from `Activity.onUserInteraction()`, which the framework calls for every
 * touch and key event dispatched to the activity — including taps consumed by
 * Compose. Doing it there rather than with a root-level pointer modifier means
 * interaction anywhere in the app counts, and no gesture is intercepted or
 * altered on the way to the UI.
 */
object ScreenInteractionTracker {

    private val _lastInteractionAtMs = MutableStateFlow(android.os.SystemClock.elapsedRealtime())

    /** Monotonic timestamp of the last user interaction. */
    val lastInteractionAtMs: StateFlow<Long> = _lastInteractionAtMs.asStateFlow()

    /** Record an interaction (called from the activity, main thread). */
    fun touch() {
        _lastInteractionAtMs.value = android.os.SystemClock.elapsedRealtime()
    }
}
