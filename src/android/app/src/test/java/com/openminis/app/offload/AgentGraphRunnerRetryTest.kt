package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentGraphRunner.retryBackoffMs] guards node retries against provider
 * bursts. The MiniMax outage of 28.10 lasted ~25 seconds; with the old flat
 * 2s pause every attempt of a 3-attempt node fell inside the burst and the
 * node gave up (journal: FAIL 1/3, 2/3, 3/3 within 24s, then GIVEUP).
 * These tests pin the arithmetic that makes attempt 3 land PAST such a
 * burst, and the cap that keeps a long outage from stalling the graph.
 */
class AgentGraphRunnerRetryTest {

    private fun backoff(attemptsMade: Int, baseMs: Long = 2_000L): Long =
        AgentGraphRunner.retryBackoffMs(attemptsMade, baseMs)

    @Test
    fun `first pause is floored at 8s however small the configured base`() {
        assertEquals(8_000L, backoff(1, baseMs = 2_000L))
        assertEquals(8_000L, backoff(1, baseMs = 500L))
    }

    @Test
    fun `a larger configured base is honoured, not reduced`() {
        assertEquals(30_000L, backoff(1, baseMs = 30_000L))
    }

    @Test
    fun `pauses double so attempt 3 clears a 25s burst`() {
        val afterFirst = backoff(1)
        val afterSecond = backoff(2)
        assertEquals(16_000L, afterSecond)
        assertTrue(
            "attempt 3 must start past the observed 25s burst, was ${afterFirst + afterSecond}ms",
            afterFirst + afterSecond >= 24_000L,
        )
    }

    @Test
    fun `very late retries are capped at 60s`() {
        assertEquals(32_000L, backoff(3))
        assertEquals(60_000L, backoff(4))
        assertEquals(60_000L, backoff(10))
    }

    @Test
    fun `zero attempts made still yields the floored base`() {
        assertEquals(8_000L, backoff(0))
    }

    @Test
    fun `anomalous huge base is capped before shl so it cannot overflow`() {
        assertEquals(60_000L, backoff(1, baseMs = 10_000_000_000L))
        assertEquals(60_000L, backoff(4, baseMs = 10_000_000_000L))
    }
}
