package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-aimd] AIMD lane controller contract:
 * - grows additively on success up to MAX_LANES;
 * - halves multiplicatively on rate markers, floor at MIN_LANES;
 * - remembers the last stable speed per provider (mostly-successful runs only).
 */
class CompactSpeedControlTest {

    @Test
    fun `additive increase caps at max`() {
        val run = CompactSpeedControl.Run(2)
        repeat(10) { run.onSuccess() }
        assertEquals(CompactSpeedControl.MAX_LANES, run.current)
    }

    @Test
    fun `rate marker halves the lanes`() {
        val run = CompactSpeedControl.Run(8)
        assertEquals(4, run.onRateMarker())
        assertEquals(2, run.onRateMarker())
        assertEquals(1, run.onRateMarker())
        assertEquals(1, run.onRateMarker()) // floor
    }

    @Test
    fun `start lanes come from stable memory`() {
        val run = CompactSpeedControl.Run(6)
        run.finish("provider-A", windowsSucceeded = 5, windowsTotal = 6)
        assertEquals(6, CompactSpeedControl.startLanes("provider-A"))
        // Mostly-failed run must NOT poison the memory.
        val bad = CompactSpeedControl.Run(4)
        bad.finish("provider-B", windowsSucceeded = 1, windowsTotal = 6)
        assertEquals(CompactSpeedControl.MIN_LANES, CompactSpeedControl.startLanes("provider-B"))
        assertEquals(CompactSpeedControl.MIN_LANES, CompactSpeedControl.startLanes("unknown"))
        assertEquals(CompactSpeedControl.MIN_LANES, CompactSpeedControl.startLanes(null))
    }

    @Test
    fun `clamped initial`() {
        val run = CompactSpeedControl.Run(99)
        assertEquals(CompactSpeedControl.MAX_LANES, run.current)
        assertTrue(CompactSpeedControl.Run(-1).current >= CompactSpeedControl.MIN_LANES)
    }
}
