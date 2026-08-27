package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleConnectionPolicyTest {

    @Test
    fun `the TTFB watchdog message triggers pre-retry eviction`() {
        // Exact text produced by the providers' stale-connection watchdog.
        val detail = "no response from server (120s) — check network/proxy"
        assertTrue(StaleConnectionPolicy.isStaleConnection(detail))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry(detail))
    }

    @Test
    fun `ordinary transient errors do not evict live sockets`() {
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("[503] upstream unavailable"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("connection reset by peer"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry(null))
    }

    @Test
    fun `pre-flight evicts only after the idle threshold`() {
        val t = StaleConnectionPolicy.STALE_IDLE_THRESHOLD_MS
        // Back-to-back agent-loop turn: fresh socket, keep it.
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRequest(0))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRequest(t - 1))
        // Idle past threshold (first turn after a pause / compaction): dial fresh.
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRequest(t))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRequest(t + 60_000))
    }

    @Test
    fun `pre-flight never evicts when activity was never observed`() {
        // Caller signals "no activity ever" with a negative idle value —
        // nothing is pooled, so there is nothing stale to drop.
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRequest(-1))
    }
}
