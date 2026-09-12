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
     * Fade durations, in milliseconds.
     *
     * Asymmetric on purpose. Fading IN is ambient — it happens while the user is
     * not looking, so it can be slow enough to avoid a jarring blink. Fading OUT
     * is a direct response to a deliberate gesture, and anything above ~150ms
     * there reads as the app being slow to wake.
     *
     * The overlay used to appear and vanish instantly, which on a full-screen
     * black surface looks like the display glitching rather than a UI state
     * changing.
     */
    const val FADE_IN_MS = 260

    /** @see FADE_IN_MS */
    const val FADE_OUT_MS = 140
}

/**
 * [T-android-screen-dim wake-gesture] Accumulator deciding whether the finger
 * movement on the black overlay is a deliberate "wake up" gesture.
 *
 * ## Why a gesture and not a tap
 *
 * A tap is what the user's palm, a pocket, or a sleeve produces by accident. The
 * overlay covers a running session, so waking it by accident both lights an
 * AMOLED panel for no reason and — before this existed — could deliver that same
 * touch to whatever control happened to be underneath.
 *
 * So waking requires movement, and specifically movement that reverses: swipe
 * one way, then back. A single long swipe is something a hand brushing the
 * screen can produce; a reversal within one gesture is not. This is the same
 * reasoning behind "swipe left and right to unlock" on wearables.
 *
 * ## Contract
 *
 * The instance is stateful and single-gesture: [reset] on pointer-down,
 * [accumulate] per movement, and the moment it returns true the caller wakes the
 * screen. Distances are in PIXELS — the caller converts from dp so thresholds
 * mean the same physical travel on any density.
 */
class ScreenDimWakeGesture {

    private var travelPx = 0f
    private var directionChanges = 0
    private var lastDx = 0f
    private var lastDy = 0f

    /** Begin a new gesture. Called on pointer-down. */
    fun reset() {
        travelPx = 0f
        directionChanges = 0
        lastDx = 0f
        lastDy = 0f
    }

    /**
     * Feed one movement delta.
     *
     * @param dx horizontal movement since the previous event, in pixels.
     * @param dy vertical movement since the previous event, in pixels.
     * @param minTravelPx total path length required, in pixels.
     * @return true once this gesture qualifies as a deliberate wake.
     */
    fun accumulate(
        dx: Float,
        dy: Float,
        minTravelPx: Float,
        minDirectionChanges: Int = MIN_DIRECTION_CHANGES,
    ): Boolean {
        // Path length, not displacement: a back-and-forth swipe ends where it
        // started, so displacement would score it as zero movement.
        travelPx += kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // A reversal is a sign flip on either axis, ignoring jitter below
        // JITTER_PX so a slow drag does not rack up "changes" from noise.
        if (kotlin.math.abs(dx) > JITTER_PX) {
            if (lastDx != 0f && sign(dx) != sign(lastDx)) directionChanges++
            lastDx = dx
        }
        if (kotlin.math.abs(dy) > JITTER_PX) {
            if (lastDy != 0f && sign(dy) != sign(lastDy)) directionChanges++
            lastDy = dy
        }

        return travelPx >= minTravelPx && directionChanges >= minDirectionChanges
    }

    /** Total path travelled in the current gesture, in pixels. Diagnostics. */
    val travelledPx: Float get() = travelPx

    /** Direction reversals seen in the current gesture. Diagnostics. */
    val reversals: Int get() = directionChanges

    private fun sign(v: Float): Int = if (v > 0f) 1 else -1

    companion object {
        /**
         * Required path length, in dp.
         *
         * Sized so a reversal cannot be satisfied by two adjacent jitter runs:
         * roughly a third of a phone's width out and back.
         */
        const val MIN_TRAVEL_DP = 200f

        /**
         * Reversals required. One means "out and back", which no single brush
         * across the screen produces.
         */
        const val MIN_DIRECTION_CHANGES = 1

        /**
         * Per-event movement below this (px) is treated as noise for the purpose
         * of direction tracking. It still counts toward travel, because slow
         * deliberate movement is real movement.
         */
        const val JITTER_PX = 2f
    }
}
