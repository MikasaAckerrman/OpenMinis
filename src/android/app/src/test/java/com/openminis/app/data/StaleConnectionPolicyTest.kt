package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleConnectionPolicyTest {

    @Test
    fun `the TTFB watchdog message triggers pre-retry eviction`() {
        // Exact text produced by OpenAIProvider's stale-connection watchdog.
        val detail = "no response from server (30s) — check network/proxy"
        assertTrue(StaleConnectionPolicy.isStaleConnection(detail))
        assertTrue(StaleConnectionPolicy.shouldEvictBeforeRetry(detail))
    }

    @Test
    fun `ordinary transient errors do not evict live sockets`() {
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("[503] upstream unavailable"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry("connection reset by peer"))
        assertFalse(StaleConnectionPolicy.shouldEvictBeforeRetry(null))
    }
}
