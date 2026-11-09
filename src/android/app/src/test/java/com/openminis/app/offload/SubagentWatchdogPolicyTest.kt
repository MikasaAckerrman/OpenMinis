package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B36] The spawn_many watchdog policy: hard per-agent ceiling (90s default,
 * override clamped to 30-180s), the stall verdict (KNOWN silence past the
 * threshold — an unknown age is alive, the hard ceiling owns that window),
 * and the degraded-member section texts. Pure decisions, unit-tested here;
 * SubagentExecutor only wires coroutines around them.
 */
class SubagentWatchdogPolicyTest {

    @Test
    fun `default ceiling is 90s`() {
        assertEquals(90_000L, SubagentWatchdogPolicy.effectiveTimeoutMs(null))
    }

    @Test
    fun `an override is honoured inside the range`() {
        assertEquals(45_000L, SubagentWatchdogPolicy.effectiveTimeoutMs(45_000L))
        assertEquals(180_000L, SubagentWatchdogPolicy.effectiveTimeoutMs(180_000L))
    }

    @Test
    fun `an out-of-range override is clamped, never disabled`() {
        assertEquals(30_000L, SubagentWatchdogPolicy.effectiveTimeoutMs(1_000L))
        assertEquals(180_000L, SubagentWatchdogPolicy.effectiveTimeoutMs(10_000_000L))
        // A bad write must not turn the watchdog OFF either way.
        assertTrue(SubagentWatchdogPolicy.effectiveTimeoutMs(-5L) >= SubagentWatchdogPolicy.MIN_TIMEOUT_MS)
    }

    @Test
    fun `unknown activity age is alive - the hard ceiling owns that window`() {
        assertFalse(SubagentWatchdogPolicy.shouldStallCancel(null))
    }

    @Test
    fun `silence at the threshold cancels, just below does not`() {
        // [B45-stall-pulse] 30s → 60s: the vc158 live report had healthy
        // batch members killed at 30.001s while their provider spent
        // 32-45s on the first tokens under 3-way parallel load.
        assertTrue(SubagentWatchdogPolicy.shouldStallCancel(60_000L))
        assertFalse(SubagentWatchdogPolicy.shouldStallCancel(59_999L))
    }

    @Test
    fun `no stream life means no stall verdict - the TTFT rule`() {
        // [B45-stall-pulse] A run whose stream has NEVER pulsed (waiting
        // for the FIRST token) is the hard ceiling's problem: the stall
        // detector owns silence AFTER life was proven.
        assertFalse(
            SubagentWatchdogPolicy.shouldStallCancel(
                300_000L,
                hasStreamLife = false,
            ),
        )
        assertTrue(
            SubagentWatchdogPolicy.shouldStallCancel(
                60_000L,
                hasStreamLife = true,
            ),
        )
    }

    @Test
    fun `a custom stall threshold is honoured`() {
        assertTrue(
            SubagentWatchdogPolicy.shouldStallCancel(
                10_000L,
                stallTimeoutMs = 10_000L,
            ),
        )
        assertFalse(
            SubagentWatchdogPolicy.shouldStallCancel(
                9_999L,
                stallTimeoutMs = 10_000L,
            ),
        )
    }

    @Test
    fun `timeout section names the ceiling in seconds`() {
        val text = SubagentWatchdogPolicy.timeoutSection(90_000L)
        assertTrue(text.contains("90s"))
        assertTrue(text.contains("Timeout"))
    }

    @Test
    fun `stall section names the silence threshold`() {
        val text = SubagentWatchdogPolicy.stallSection()
        assertTrue(text.contains("60s"))
        assertTrue(text.contains("Stalled"))
    }

    @Test
    fun `the stall sentinel never reads like a result`() {
        // The executor swaps it for stallSection before anything is emitted;
        // the sentinel must not be a plausible agent answer prefix.
        assertTrue(SubagentWatchdogPolicy.STALL_SENTINEL != "Stalled")
        assertTrue(SubagentWatchdogPolicy.STALL_SENTINEL != "Timeout")
    }
}
