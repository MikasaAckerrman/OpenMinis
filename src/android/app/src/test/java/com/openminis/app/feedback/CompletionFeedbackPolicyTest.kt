package com.openminis.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-completion-haptics] The two things worth pinning down about the
 * turn-finished buzz:
 *
 *  1. WHEN it fires. The rule that carries real weight is "not on cancel" —
 *     the user pressing Stop already knows the turn ended, and a buzz there is
 *     the app narrating the user's own tap.
 *  2. WHAT it feels like. It must stay a recognisable DOUBLE pulse. A silent
 *     edit that dropped one pulse or widened the gap would still compile,
 *     still vibrate, and no longer be the signal that was asked for — which is
 *     exactly the class of regression a unit test can catch and a human
 *     probably won't.
 */
class CompletionFeedbackPolicyTest {

    @Test
    fun completedTurn_vibratesWhenEnabled() {
        assertTrue(CompletionFeedbackPolicy.shouldVibrate(TurnOutcome.Completed, enabled = true))
    }

    @Test
    fun failedTurn_stillVibrates() {
        // A failure is news too — arguably more urgent than a success.
        assertTrue(CompletionFeedbackPolicy.shouldVibrate(TurnOutcome.Failed, enabled = true))
    }

    @Test
    fun cancelledTurn_neverVibrates() {
        assertFalse(CompletionFeedbackPolicy.shouldVibrate(TurnOutcome.Cancelled, enabled = true))
    }

    @Test
    fun disabledSetting_silencesEveryOutcome() {
        for (outcome in TurnOutcome.values()) {
            assertFalse(
                "outcome=$outcome must be silent when the setting is off",
                CompletionFeedbackPolicy.shouldVibrate(outcome, enabled = false),
            )
        }
    }

    @Test
    fun sessionThatWasNeverActive_doesNotVibrate() {
        assertFalse(
            CompletionFeedbackPolicy.shouldVibrate(
                TurnOutcome.Completed,
                enabled = true,
                wasActive = false,
            ),
        )
    }

    @Test
    fun pattern_isTwoPulsesWithAlignedAmplitudes() {
        val timings = CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS
        val amplitudes = CompletionFeedbackPolicy.DOUBLE_PULSE_AMPLITUDES

        // VibrationEffect.createWaveform throws when these disagree in length.
        assertEquals(timings.size, amplitudes.size)
        // Waveform semantics: even indices are waits, odd indices are pulses.
        assertEquals(0L, timings[0])
        assertTrue(timings.indices.filter { it % 2 == 0 }.all { amplitudes[it] == 0 })
        assertTrue(timings.indices.filter { it % 2 == 1 }.all { amplitudes[it] > 0 })
        // "Double" is the requirement, not an implementation detail.
        assertEquals(2, amplitudes.count { it > 0 })
    }

    @Test
    fun pattern_gapKeepsTheTwoPulsesDistinct() {
        // Under ~60ms a slow LRA smears the pair into one long buzz; over
        // ~150ms they stop reading as one signal.
        val gap = CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS[2]
        assertTrue("gap=$gap ms", gap in 60L..150L)
    }

    @Test
    fun pattern_isShortAndNotStartling() {
        assertTrue(CompletionFeedbackPolicy.DOUBLE_PULSE_DURATION_MS < 250)
        assertEquals(
            CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS.sum(),
            CompletionFeedbackPolicy.DOUBLE_PULSE_DURATION_MS,
        )
        // Full 255 is an alarm, not a notification.
        assertTrue(CompletionFeedbackPolicy.DOUBLE_PULSE_AMPLITUDES.all { it <= 200 })
    }
}
