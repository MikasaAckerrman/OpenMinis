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

    // ── bad gateway (the user's run of 502s) ────────────────────────────────

    @Test
    fun `502 in both provider text shapes classifies as bad gateway`() {
        // OpenAIProvider builds "[<type>] <message>" from a JSON error body and
        // "HTTP <code>: <body>" when the body is not JSON. Both must be caught.
        assertEquals(
            TransientRetryBudget.Kind.BAD_GATEWAY,
            TransientRetryBudget.classify("[502] Bad Gateway"),
        )
        assertEquals(
            TransientRetryBudget.Kind.BAD_GATEWAY,
            TransientRetryBudget.classify("HTTP 502: <html>502 Bad Gateway</html>"),
        )
        assertEquals(
            TransientRetryBudget.Kind.BAD_GATEWAY,
            TransientRetryBudget.classify("HTTP 504: gateway timeout"),
        )
        assertEquals(
            TransientRetryBudget.Kind.BAD_GATEWAY,
            TransientRetryBudget.classify("upstream connect error or disconnect/reset"),
        )
    }

    @Test
    fun `a request id containing 502 is not a bad gateway`() {
        // Why the markers are anchored and not bare digits: provider text carries
        // request ids and model names. ChatViewModel's own 5xx detection hit this
        // exact trap, which is why it anchors on the bracket/prefix form too.
        assertEquals(
            TransientRetryBudget.Kind.GENERIC,
            TransientRetryBudget.classify("[req_a502f3] model overloaded"),
        )
        assertEquals(
            TransientRetryBudget.Kind.GENERIC,
            TransientRetryBudget.classify("Transient error: x-request-id=8502341"),
        )
    }

    @Test
    fun `a dead socket wins over a bad-gateway word`() {
        // CONNECTION is checked first because it is the only class that acts on
        // our own transport (pool eviction). A bad-gateway retry must not evict a
        // healthy pool.
        val kind = TransientRetryBudget.classify("connection closed by bad gateway")
        assertEquals(TransientRetryBudget.Kind.CONNECTION, kind)
        assertTrue(TransientRetryBudget.evictsPool(kind))
    }

    @Test
    fun `bad gateway gets a longer ladder and neither await nor eviction`() {
        val k = TransientRetryBudget.Kind.BAD_GATEWAY
        assertEquals(5, TransientRetryBudget.maxAttempts(k))
        // The relay answers 502 instantly, so 1/2/4 would burn the whole budget
        // in ~7s — usually before the upstream switch completes.
        assertEquals(2, TransientRetryBudget.delaySecForAttempt(k, 0))
        assertEquals(4, TransientRetryBudget.delaySecForAttempt(k, 1))
        assertEquals(6, TransientRetryBudget.delaySecForAttempt(k, 2))
        assertEquals(10, TransientRetryBudget.delaySecForAttempt(k, 3))
        // Plateaus rather than growing without bound: past ~10s a fallback
        // provider serves the user better than more waiting.
        assertEquals(10, TransientRetryBudget.delaySecForAttempt(k, 99))
        // Our sockets are fine and the device is online — touching either would
        // be treating a server-side fault as a client-side one.
        assertFalse(TransientRetryBudget.evictsPool(k))
        assertFalse(TransientRetryBudget.awaitsConnectivity(k))
    }

    @Test
    fun `every kind has a bounded budget and a bounded delay`() {
        // Guards the shape of the policy itself: a new Kind added without a
        // maxAttempts branch would fail to compile, but one added with a runaway
        // value would not — so assert the invariants.
        for (kind in TransientRetryBudget.Kind.entries) {
            val attempts = TransientRetryBudget.maxAttempts(kind)
            assertTrue("$kind attempts=$attempts", attempts in 1..10)
            for (attempt in 0 until attempts) {
                val d = TransientRetryBudget.delaySecForAttempt(kind, attempt)
                assertTrue("$kind attempt=$attempt delay=$d", d in 0..15)
            }
        }
    }

    @Test
    fun `offline gets the largest budget, generic keeps the old three`() {
        assertEquals(6, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.OFFLINE))
        assertEquals(4, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.CONNECTION))
        assertEquals(5, TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.BAD_GATEWAY))
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
