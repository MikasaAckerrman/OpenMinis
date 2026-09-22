package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-turn-timer] The timer's user-facing contract: the agent must always see
 * the remaining time, and expiry must force a final summary, not silent
 * overruns.
 */
class TurnTimerPolicyTest {

    @Test
    fun `format renders minutes and hours correctly`() {
        assertEquals("43:12", TurnTimerPolicy.format((43 * 60 + 12) * 1000L))
        assertEquals("1:02:33", TurnTimerPolicy.format(((1 * 3600) + 2 * 60 + 33) * 1000L))
        assertEquals("00:05", TurnTimerPolicy.format(5000L))
    }

    @Test
    fun `expired budget formats as EXPIRED`() {
        assertEquals("EXPIRED", TurnTimerPolicy.format(0))
        assertEquals("EXPIRED", TurnTimerPolicy.format(-1000))
    }

    @Test
    fun `remaining math is deadline minus now`() {
        assertEquals(90_000L, TurnTimerPolicy.remainingMs(deadlineMs = 110_000, nowMs = 20_000))
    }

    @Test
    fun `the running line always shows the time left`() {
        val line = TurnTimerPolicy.line(remainingMs = (10 * 60) * 1000L, totalMs = (60 * 60) * 1000L)
        assertTrue(line.contains("10:00"))
        assertTrue(line.contains("turn timer"))
    }

    @Test
    fun `under fifteen percent the line asks to plan the wrap-up`() {
        val line = TurnTimerPolicy.line(remainingMs = (5 * 60) * 1000L, totalMs = (60 * 60) * 1000L)
        assertTrue(line.contains("wrap up"))
    }

    @Test
    fun `expiry demands the final summary and forbids new work`() {
        val line = TurnTimerPolicy.line(remainingMs = 0, totalMs = 600_000L)
        assertTrue(line.contains("TIME IS UP"))
        assertTrue(line.contains("done"))
        assertTrue(line.contains("not"))
        assertTrue(line.lowercase().contains("do not start"))
    }

    @Test
    fun `the hard refusal names the required summary shape`() {
        val r = TurnTimerPolicy.refusal()
        assertTrue(r.contains("DONE"))
        assertTrue(r.contains("NOT DONE"))
        assertTrue(r.contains("WHAT REMAINS"))
    }
}
