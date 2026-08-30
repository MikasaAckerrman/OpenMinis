package com.openminis.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE
import com.openminis.app.ui.settings.KEY_SCREEN_DIM_DELAY_SEC
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import com.openminis.app.ui.settings.screenDimDelaySeconds
import kotlinx.coroutines.delay

/**
 * [T-android-screen-dim] Full-screen black overlay shown after a configurable
 * idle period while a task is running and keep-screen-awake is on.
 *
 * Why: holding the screen ON is what stops aggressive OEM power management from
 * parking the radio mid-task (see the stale-socket work in NetworkMonitor), but a
 * lit AMOLED panel costs battery and risks burn-in over a long run. Pure black on
 * AMOLED means those pixels are physically off, so the link stays up at close to
 * zero display cost.
 *
 * Rendered at the root of the composition with a high [zIndex] so it covers every
 * screen. Idle time comes from [ScreenInteractionTracker] (fed by
 * `onUserInteraction`), so every touch in the app counts and no gesture is
 * intercepted while the UI is visible.
 *
 * ## Waking
 *
 * Requires a deliberate back-and-forth swipe ([ScreenDimWakeGesture]), and while
 * the overlay is up NO touch reaches the app underneath. Both follow from the
 * same fact: the overlay is a black rectangle over a running session, so the
 * user cannot see what they are pressing. A tap-to-wake that also delivered its
 * touch downward meant a pocket or a palm could hit Stop, Clear Chat or a send
 * button blind.
 */
@Composable
fun ScreenDimOverlay() {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val prefs = remember { getAppearancePrefs(context) }

    // Re-read on change so toggling either setting takes effect immediately.
    var delaySec by remember { mutableIntStateOf(screenDimDelaySeconds(context)) }
    var keepAwake by remember { mutableStateOf(keepScreenAwakeEnabled(context)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_SCREEN_DIM_DELAY_SEC -> delaySec = screenDimDelaySeconds(context)
                KEY_KEEP_SCREEN_AWAKE -> keepAwake = keepScreenAwakeEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
    val hasActiveTask = activeSessions.isNotEmpty()
    val lastTouchAt by ScreenInteractionTracker.lastInteractionAtMs.collectAsState()

    var dimmed by remember { mutableStateOf(false) }

    // Inert unless enabled AND a task is running, so the ticker costs nothing in
    // the common case. Restarts on every interaction (lastTouchAt changes).
    val enabled = keepAwake && delaySec > 0 && hasActiveTask
    LaunchedEffect(enabled, delaySec, lastTouchAt) {
        if (!enabled) {
            dimmed = false
            return@LaunchedEffect
        }
        dimmed = false
        while (true) {
            val idleMs = android.os.SystemClock.elapsedRealtime() - lastTouchAt
            if (ScreenDimPolicy.shouldDim(keepAwake, delaySec, hasActiveTask, idleMs)) {
                dimmed = true
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }

    // [T-android-screen-dim fullscreen] Hide the system bars while dimmed. A
    // black Box covers only the app's own surface: the OriginOS status bar
    // (clock, battery) and the navigation bar are drawn by the system ON TOP of
    // it, so without this the "black screen" is a black rectangle framed by two
    // lit strips — exactly the pixels burn-in mitigation is trying to switch off.
    // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE keeps the bars reachable by a swipe
    // instead of trapping the user, and hiding is reverted both when the overlay
    // lifts and on dispose, so the bars can never be left gone.
    //
    // Screen BRIGHTNESS is deliberately NOT touched. Overriding it fought the
    // system's own auto-brightness: on wake the panel came back at whatever
    // level the override had forced rather than what the ambient sensor wanted,
    // which read as the screen being broken. Pure black on AMOLED already
    // switches those pixels off, so the backlight override bought nothing.
    DisposableEffect(dimmed) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let {
            val insets = androidx.core.view.WindowCompat.getInsetsController(it, view)
            if (dimmed) {
                insets.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                insets.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, view)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Fade rather than a hard cut: a full-screen black surface appearing or
    // vanishing instantly looks like the display glitching, not like a UI state
    // changing. Asymmetric timing — see ScreenDimPolicy.FADE_IN_MS.
    AnimatedVisibility(
        visible = dimmed,
        enter = fadeIn(tween(ScreenDimPolicy.FADE_IN_MS)),
        exit = fadeOut(tween(ScreenDimPolicy.FADE_OUT_MS)),
        modifier = Modifier.zIndex(1000f),
    ) {
        val minTravelPx = remember(density) {
            with(density) { ScreenDimWakeGesture.MIN_TRAVEL_DP.dp.toPx() }
        }
        val wake = remember { ScreenDimWakeGesture() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(minTravelPx) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial pass + consume: the touch must not reach
                            // the UI underneath. The user cannot see what is
                            // there, so any pass-through is a blind press on
                            // Stop / Clear Chat / send.
                            val down = awaitPointerEvent(PointerEventPass.Initial)
                            down.changes.forEach { it.consume() }
                            wake.reset()
                            var woke = false
                            // Track this gesture until every finger is up. The
                            // `pressed` flag has to be re-read from each new
                            // event — `down.changes` is a snapshot and never
                            // updates, so testing it would loop forever.
                            var pressed = down.changes.any { it.pressed }
                            while (pressed) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                ev.changes.forEach { c ->
                                    c.consume()
                                    val d = c.positionChange()
                                    if (!woke && wake.accumulate(d.x, d.y, minTravelPx)) {
                                        woke = true
                                    }
                                }
                                pressed = ev.changes.any { it.pressed }
                            }
                            if (woke) {
                                // Only a qualifying gesture counts as activity;
                                // otherwise an accidental brush would restart the
                                // idle timer and keep the panel lit.
                                ScreenInteractionTracker.touch()
                                dimmed = false
                            }
                        }
                    }
                },
        )
    }
}
