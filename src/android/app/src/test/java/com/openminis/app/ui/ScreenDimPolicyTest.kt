package com.openminis.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-screen-dim] Rules for the black idle overlay.
 *
 * The overlay hides the UI, so a false positive is user-visible and annoying:
 * these cover every gate independently to prove the feature is inert unless all
 * four conditions hold.
 */
class ScreenDimPolicyTest {

    private val delay = 60
    private val past = 60_000L

    @Test
    fun `dims once idle reaches the configured delay`() {
        assertTrue(ScreenDimPolicy.shouldDim(true, delay, true, past))
    }

    @Test
    fun `does not dim one millisecond early`() {
        assertFalse(ScreenDimPolicy.shouldDim(true, delay, true, past - 1))
    }

    @Test
    fun `does not dim when keep-awake is off`() {
        // Without keep-awake the system sleeps the screen on its own; an
        // overlay would only blank the UI before that happens.
        assertFalse(ScreenDimPolicy.shouldDim(false, delay, true, past))
    }

    @Test
    fun `does not dim when delay is zero (feature off)`() {
        assertFalse(ScreenDimPolicy.shouldDim(true, 0, true, past))
    }

    @Test
    fun `does not dim on negative delay`() {
        assertFalse(ScreenDimPolicy.shouldDim(true, -5, true, past))
    }

    @Test
    fun `does not dim when no task is running`() {
        // THE important guard: blanking the screen while the user is reading an
        // idle app would look like a crash.
        assertFalse(ScreenDimPolicy.shouldDim(true, delay, false, past))
    }

    @Test
    fun `does not dim immediately after interaction`() {
        assertFalse(ScreenDimPolicy.shouldDim(true, delay, true, 0L))
    }

    @Test
    fun `short delay option works`() {
        assertTrue(ScreenDimPolicy.shouldDim(true, 30, true, 30_000L))
        assertFalse(ScreenDimPolicy.shouldDim(true, 30, true, 29_999L))
    }

    @Test
    fun `long delay option works`() {
        assertTrue(ScreenDimPolicy.shouldDim(true, 600, true, 600_000L))
        assertFalse(ScreenDimPolicy.shouldDim(true, 600, true, 599_000L))
    }

    @Test
    fun `fade in is slower than fade out`() {
        // Fading in is ambient (nobody is watching); fading out answers a
        // deliberate gesture, and a slow wake reads as the app being stuck.
        assertTrue(ScreenDimPolicy.FADE_IN_MS > ScreenDimPolicy.FADE_OUT_MS)
        // Both fast enough to read as a transition rather than a wait.
        assertTrue(ScreenDimPolicy.FADE_IN_MS in 120..400)
        assertTrue(ScreenDimPolicy.FADE_OUT_MS in 80..200)
    }
}

/**
 * [T-android-screen-dim wake-gesture] Waking the black overlay requires a
 * deliberate back-and-forth swipe.
 *
 * A tap is what a palm, a pocket or a sleeve produces by accident, so these
 * cover the accidental shapes explicitly — the point of the gesture is that the
 * cheap accidental inputs do NOT satisfy it.
 */
class ScreenDimWakeGestureTest {

    /** 200dp of travel at 3x density, the value the overlay passes in. */
    private val minTravel = ScreenDimWakeGesture.MIN_TRAVEL_DP * 3f

    private fun gesture() = ScreenDimWakeGesture()

    @Test
    fun `a tap does not wake`() {
        val g = gesture()
        g.reset()
        assertFalse(g.accumulate(0f, 0f, minTravel))
    }

    @Test
    fun `a single long swipe does not wake`() {
        // A hand brushing across the screen is exactly this shape.
        val g = gesture()
        g.reset()
        var woke = false
        repeat(40) { woke = woke || g.accumulate(30f, 0f, minTravel) }
        assertFalse("one-directional swipe must not wake", woke)
        assertTrue("but it did travel far enough", g.travelledPx >= minTravel)
    }

    @Test
    fun `out and back wakes`() {
        val g = gesture()
        g.reset()
        var woke = false
        repeat(20) { woke = woke || g.accumulate(30f, 0f, minTravel) }
        repeat(20) { woke = woke || g.accumulate(-30f, 0f, minTravel) }
        assertTrue(woke)
    }

    @Test
    fun `a reversal alone is not enough without distance`() {
        // Small wiggle: reverses, but goes nowhere.
        val g = gesture()
        g.reset()
        var woke = false
        repeat(4) {
            woke = woke || g.accumulate(10f, 0f, minTravel)
            woke = woke || g.accumulate(-10f, 0f, minTravel)
        }
        assertFalse(woke)
    }

    @Test
    fun `vertical back and forth also wakes`() {
        val g = gesture()
        g.reset()
        var woke = false
        repeat(20) { woke = woke || g.accumulate(0f, 30f, minTravel) }
        repeat(20) { woke = woke || g.accumulate(0f, -30f, minTravel) }
        assertTrue(woke)
    }

    @Test
    fun `jitter does not count as a reversal`() {
        // Sub-pixel noise on a slow drag must not manufacture direction changes,
        // or a resting finger would wake the screen.
        val g = gesture()
        g.reset()
        var woke = false
        repeat(200) { i ->
            val jitter = if (i % 2 == 0) 1f else -1f
            woke = woke || g.accumulate(20f + jitter * 0.5f, jitter, minTravel)
        }
        assertFalse(woke)
        assertTrue(g.travelledPx >= minTravel)
        assertTrue("no reversals from noise", g.reversals == 0)
    }

    @Test
    fun `reset clears state between gestures`() {
        // Otherwise two separate accidental brushes in opposite directions would
        // add up to one qualifying gesture.
        val g = gesture()
        g.reset()
        repeat(20) { g.accumulate(30f, 0f, minTravel) }
        g.reset()
        var woke = false
        repeat(20) { woke = woke || g.accumulate(-30f, 0f, minTravel) }
        assertFalse(woke)
    }

    @Test
    fun `travel accumulates as path length, not displacement`() {
        // Out and back ends where it started; displacement would score zero.
        val g = gesture()
        g.reset()
        g.accumulate(100f, 0f, minTravel)
        g.accumulate(-100f, 0f, minTravel)
        assertTrue(g.travelledPx == 200f)
    }

    @Test
    fun `diagonal movement counts its real length`() {
        val g = gesture()
        g.reset()
        g.accumulate(30f, 40f, minTravel)
        assertTrue("hypot, not max/sum", g.travelledPx == 50f)
    }
}
