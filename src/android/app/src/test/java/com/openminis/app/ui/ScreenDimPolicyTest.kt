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
    fun `dimmed brightness is minimum, not the restore sentinel`() {
        // -1f means "system default"; using it while dimmed would leave the
        // panel lit. 0f is minimum backlight.
        assertTrue(ScreenDimPolicy.DIMMED_BRIGHTNESS == 0f)
        assertTrue(ScreenDimPolicy.RESTORE_BRIGHTNESS == -1f)
    }
}
