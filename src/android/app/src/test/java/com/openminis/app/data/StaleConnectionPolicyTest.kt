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
    fun `connection-level faults trigger pre-retry eviction`() {
        // The socket itself is suspect — force a fresh dial before retrying.
        assertTrue(StaleConnectionPolicy.isConnectionFault("Network error: stream was reset: INTERNAL_ERROR"))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry("Network error: stream was reset: INTERNAL_ERROR"))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry("Network error: unexpected end of stream"))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry("Network error: connection reset by peer"))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry("Network error: stream was reset: NO_ERROR (GOAWAY)"))
    }

    @Test
    fun `ordinary transient errors do not evict live sockets`() {
        // A real HTTP error delivered by a healthy socket — keep the pool.
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("[503] upstream unavailable"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("[429] rate limited"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("timeout waiting for tokens"))
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
