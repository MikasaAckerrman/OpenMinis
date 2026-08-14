package com.openminis.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-haptics-customization] The buzz-shape axes.
 *
 * What is worth pinning down here is not "does an enum round-trip" for its own
 * sake but three specific breakages:
 *
 *  1. A waveform whose `timings` and `amplitudes` disagree in length makes
 *     `VibrationEffect.createWaveform` THROW at runtime — on the turn-teardown
 *     path. The composition builds both arrays together precisely so that can't
 *     happen; this test is what keeps it that way.
 *  2. Scaling a pattern down must not merge its pulses. A "triple" that feels
 *     like one long buzz at the SHORT setting is silently the wrong pattern.
 *  3. An unknown id (older build reading a newer preference, or a hand-edited
 *     pref) must fall back to the default rather than throwing — the value is
 *     read on every turn.
 */
class VibrationPatternsTest {

    @Test
    fun defaultProfileIsTheShippedDoublePulse() {
        // The default must not drift as patterns are added: existing users who
        // never open the picker keep the buzz they already know.
        assertTrue(
            CompletionFeedbackPolicy.DOUBLE_PULSE_TIMINGS.contentEquals(longArrayOf(0, 40, 90, 40)),
        )
        assertTrue(
            CompletionFeedbackPolicy.DOUBLE_PULSE_AMPLITUDES.contentEquals(intArrayOf(0, 180, 0, 180)),
        )
        assertEquals(VibrationPattern.DOUBLE, VibrationPattern.DEFAULT)
    }

    @Test
    fun everyCombinationProducesAWellFormedWaveform() {
        for (p in VibrationPattern.values()) {
            for (i in VibrationIntensity.values()) {
                for (l in VibrationLength.values()) {
                    val w = VibrationPatterns.waveform(VibrationProfile(p, i, l))
                    val tag = "${p.id}/${i.id}/${l.id}"
                    // createWaveform throws when these disagree.
                    assertEquals("$tag array lengths", w.timings.size, w.amplitudes.size)
                    assertEquals("$tag leading wait", 0L, w.timings[0])
                    for (k in w.timings.indices) {
                        if (k % 2 == 1) {
                            assertTrue("$tag pulse[$k] must be audible", w.timings[k] >= 1L)
                            assertEquals("$tag amp[$k]", i.amplitude, w.amplitudes[k])
                        } else {
                            assertEquals("$tag gap amp[$k]", 0, w.amplitudes[k])
                            if (k > 0) {
                                assertTrue(
                                    "$tag gap[$k]=${w.timings[k]} would smear the pulses",
                                    w.timings[k] >= VibrationPatterns.MIN_GAP_MS,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun pulseCountsMatchWhatEachPatternPromises() {
        fun pulses(p: VibrationPattern) =
            VibrationPatterns.waveform(VibrationProfile(pattern = p)).pulseCount
        assertEquals(2, pulses(VibrationPattern.DOUBLE))
        assertEquals(1, pulses(VibrationPattern.SINGLE_LONG))
        assertEquals(3, pulses(VibrationPattern.TRIPLE))
        assertEquals(2, pulses(VibrationPattern.SHORT_LONG))
        assertEquals(4, pulses(VibrationPattern.HEARTBEAT))
        assertEquals(1, pulses(VibrationPattern.LONG_RUMBLE))
    }

    @Test
    fun shortestSettingKeepsTripleATriple() {
        val w = VibrationPatterns.waveform(
            VibrationProfile(pattern = VibrationPattern.TRIPLE, length = VibrationLength.SHORT),
        )
        assertEquals(3, w.pulseCount)
        val gaps = w.timings.filterIndexed { idx, _ -> idx % 2 == 0 && idx > 0 }
        assertTrue(gaps.all { it >= VibrationPatterns.MIN_GAP_MS })
    }

    @Test
    fun lengthAxisIsMonotonic() {
        for (p in VibrationPattern.values()) {
            val durations = VibrationLength.values().map {
                VibrationPatterns.waveform(VibrationProfile(pattern = p, length = it)).durationMs
            }
            assertEquals("${p.id} durations must strictly increase", durations.sorted(), durations)
            assertEquals("${p.id} durations must all differ", durations.size, durations.distinct().size)
        }
    }

    @Test
    fun intensityAxisMapsStraightThrough() {
        for (i in VibrationIntensity.values()) {
            val w = VibrationPatterns.waveform(VibrationProfile(intensity = i))
            assertTrue(w.amplitudes.filter { it > 0 }.all { it == i.amplitude })
        }
        assertTrue(VibrationIntensity.LIGHT.amplitude < VibrationIntensity.MEDIUM.amplitude)
        assertTrue(VibrationIntensity.MEDIUM.amplitude < VibrationIntensity.STRONG.amplitude)
        // Android's amplitude ceiling; a higher value is rejected.
        assertEquals(255, VibrationIntensity.STRONG.amplitude)
    }

    @Test
    fun idsRoundTripAndUnknownFallsBackToDefault() {
        for (p in VibrationPattern.values()) assertEquals(p, VibrationPattern.fromId(p.id))
        for (i in VibrationIntensity.values()) assertEquals(i, VibrationIntensity.fromId(i.id))
        for (l in VibrationLength.values()) assertEquals(l, VibrationLength.fromId(l.id))

        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromId("from-a-newer-build"))
        assertEquals(VibrationPattern.DEFAULT, VibrationPattern.fromId(null))
        assertEquals(VibrationIntensity.DEFAULT, VibrationIntensity.fromId(""))
        assertEquals(VibrationLength.DEFAULT, VibrationLength.fromId(null))
    }

    @Test
    fun bypassDndDefaultsOff() {
        // A completion buzz that punches through Do Not Disturb must be opt-in.
        assertFalse(VibrationProfile().bypassDnd)
    }
}
