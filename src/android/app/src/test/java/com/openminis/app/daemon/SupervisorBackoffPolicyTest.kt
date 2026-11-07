package com.openminis.app.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [M-R1] The crash-backoff escalation: 1s -> 2s -> ... -> 60s cap, a
 * death after a stable (>60 s) uptime resets the escalation, a
 * deliberate clean stop is not a crash. Pure JVM.
 */
class SupervisorBackoffPolicyTest {

    @Test
    fun `the first crash delays by the base interval`() {
        val policy = SupervisorBackoffPolicy()
        assertEquals(1_000L, policy.onDeath(uptimeMs = 100))
    }

    @Test
    fun `a rapid crash loop doubles up to the 60-second cap`() {
        val policy = SupervisorBackoffPolicy()
        val delays = (1..8).map { policy.onDeath(uptimeMs = 50) }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `a death after a stable uptime resets the escalation`() {
        val policy = SupervisorBackoffPolicy()
        assertEquals(1_000L, policy.onDeath(100))
        assertEquals(2_000L, policy.onDeath(100))
        assertEquals(4_000L, policy.onDeath(100))
        // Ran 61 s, then died — a fresh start, not an escalation.
        assertEquals(1_000L, policy.onDeath(uptimeMs = 60_001))
    }

    @Test
    fun `uptime exactly at the stability boundary does not reset`() {
        val policy = SupervisorBackoffPolicy()
        assertEquals(1_000L, policy.onDeath(100))
        assertEquals(2_000L, policy.onDeath(100))
        // Strictly-greater rule: exactly 60 s is still inside the window.
        assertEquals(4_000L, policy.onDeath(uptimeMs = 60_000))
    }

    @Test
    fun `a deliberate clean stop resets the escalation`() {
        val policy = SupervisorBackoffPolicy()
        assertEquals(1_000L, policy.onDeath(100))
        assertEquals(2_000L, policy.onDeath(100))
        assertEquals(2, policy.currentConsecutiveFailures())
        policy.onCleanStop()
        assertEquals(0, policy.currentConsecutiveFailures())
        assertEquals(1_000L, policy.onDeath(100))
    }

    @Test
    fun `the delay never overflows no matter how long the crash loop runs`() {
        val policy = SupervisorBackoffPolicy()
        var last = 0L
        repeat(500) { last = policy.onDeath(uptimeMs = 10) }
        assertEquals(60_000L, last)
    }
}
