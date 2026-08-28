package com.openminis.app.ui

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
import androidx.compose.ui.platform.LocalContext
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
 * zero display cost. Any interaction restores the UI instantly.
 *
 * Rendered at the root of the composition with a high [zIndex] so it covers every
 * screen. Idle time comes from [ScreenInteractionTracker] (fed by
 * `onUserInteraction`), so every touch in the app counts and no gesture is
 * intercepted while the UI is visible.
 */
@Composable
fun ScreenDimOverlay() {
    val context = LocalContext.current
    val view = LocalView.current
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

    // Drive the backlight down while dimmed, hand control back on exit — so a
    // dimmed window can never be left stranded dark.
    DisposableEffect(dimmed) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let {
            it.attributes = it.attributes.apply {
                screenBrightness = if (dimmed) {
                    ScreenDimPolicy.DIMMED_BRIGHTNESS
                } else {
                    ScreenDimPolicy.RESTORE_BRIGHTNESS
                }
            }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = ScreenDimPolicy.RESTORE_BRIGHTNESS
                }
            }
        }
    }

    if (dimmed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1000f)
                .background(Color.Black)
                .pointerInput(Unit) {
                    // Consume the waking touch so it cannot also press a
                    // control underneath the overlay.
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            ScreenInteractionTracker.touch()
                            dimmed = false
                        }
                    }
                },
        )
    }
}
