package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-offline-retry-budget] The two error texts in these tests are verbatim from
 * the user's device (gorouter.app resolve failure, "connection closed"). They are
 * the whole reason this policy exists, so they are asserted literally rather than
 * paraphrased.
 */
class TransientRetryBudgetTest {

    @Test
    fun `resolve failure classifies as offline`() {
        val kind = TransientRetryBudget.classify(
            "Unable to resolve host \"gorouter.app\": No address associated with hostname",
        )
        assertEquals(TransientRetryBudget.Kind.OFFLINE, kind)
        assertTrue(TransientRetryBudget.awaitsConnectivity(kind))
        assertFalse(TransientRetryBudget.evictsPool(kind))
    }

    @Test
    fun `connection closed classifies as connection and evicts pool`() {
        val kind = TransientRetryBudget.classify("connection closed")
        assertEquals(TransientRetryBudget.Kind.CONNECTION, kind)
        assertTrue(TransientRetryBudget.evictsPool(kind))
        assertFalse(TransientRetryBudget.awaitsConnectivity(kind))
    }

    @Test
    fun `stream reset and goaway are connection class`() {
        assertEquals(
            TransientRetryBudget.Kind.CONNECTION,
            TransientRetryBudget.classify("stream was reset: PROTOCOL_ERROR"),
        )
        assertEquals(
            TransientRetryBudget.Kind.CONNECTION,
            TransientRetryBudget.classify("Received GOAWAY frame"),
        )
    }

    @Test
    fun `5xx text falls back to generic`() {
        assertEquals(
            TransientRetryBudget.Kind.GENERIC,
            TransientRetryBudget.classify("HTTP 503 Service Unavailable"),
        )
        assertEquals(TransientRetryBudget.Kind.GENERIC, TransientRetryBudget.classify(null))
    }

    @Test
    fun `offline gets the largest budget, generic keeps the old three`() {
        assertEquals(6, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.OFFLINE))
        assertEquals(4, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.CONNECTION))
        // Regression guard: unclassified transient errors must not silently get
        // more retries than they had before this policy existed.
        assertEquals(3, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.GENERIC))
    }

    @Test
    fun `offline backoff stays short because connectivity await does the waiting`() {
        for (attempt in 0 until 6) {
            assertEquals(1, TransientRetryBudget.delaySecForAttempt(TransientRetryBudget.Kind.OFFLINE, attempt))
        }
    }

    @Test
    fun `generic backoff keeps 1-2-4 and then plateaus`() {
        val k = TransientRetryBudget.Kind.GENERIC
        assertEquals(1, TransientRetryBudget.delaySecForAttempt(k, 0))
        assertEquals(2, TransientRetryBudget.delaySecForAttempt(k, 1))
        assertEquals(4, TransientRetryBudget.delaySecForAttempt(k, 2))
        assertEquals(4, TransientRetryBudget.delaySecForAttempt(k, 9))
        assertEquals(0, TransientRetryBudget.delaySecForAttempt(k, -1))
    }
}
