package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-exhausted-transient-falls-back] The user reported two errors repeating
 * "constantly", each in a different session:
 *
 *   Transient error: HTTP 502: error code: 502
 *   Transient error: no response from server (120s) — check network/proxy
 *
 * Both are TransientError, and `is5xx` only matches ProviderError — so neither
 * ever set shouldFallback, and the group's other members were never tried. The
 * verbatim strings are asserted literally because they are the reason this
 * policy exists.
 */
class FallbackDecisionTest {

    private fun spent(isTransient: Boolean, used: Int, max: Int) =
        FallbackDecision.transientBudgetSpent(isTransient, used, max)

    private fun decide(
        rateLimit: Boolean = false,
        contentFilter: Boolean = false,
        http5xx: Boolean = false,
        empty: Boolean = false,
        exhausted: Boolean = false,
        always: Boolean = false,
    ) = FallbackDecision.shouldFallback(
        isRateLimit = rateLimit,
        isContentFilter = contentFilter,
        isHttp5xxProviderError = http5xx,
        isEmptyResponse = empty,
        transientRetriesExhausted = exhausted,
        strategyIsAlways = always,
    )

    // ── the reported bug ────────────────────────────────────────────────────

    @Test
    fun `502 through mapHttpError now reaches fallback once retries are spent`() {
        val msg = "Transient error: HTTP 502: error code: 502"
        val kind = TransientRetryBudget.classify(msg)
        assertEquals(TransientRetryBudget.Kind.BAD_GATEWAY, kind)
        val max = TransientRetryBudget.maxAttempts(kind)

        // Mid-budget the turn stays on this provider — a blip usually clears.
        assertFalse(decide(exhausted = spent(true, max - 1, max)))
        // Budget spent → hand over instead of failing the turn.
        assertTrue(decide(exhausted = spent(true, max, max)))
    }

    @Test
    fun `TTFB watchdog now reaches fallback once retries are spent`() {
        val msg = "Transient error: no response from server (120s) — check network/proxy"
        val kind = TransientRetryBudget.classify(msg)
        assertEquals(TransientRetryBudget.Kind.NO_RESPONSE, kind)
        val max = TransientRetryBudget.maxAttempts(kind)
        assertTrue(decide(exhausted = spent(true, max, max)))
    }

    @Test
    fun `a transient error that has NOT spent its budget does not fall back`() {
        // Jumping on the first failure would abandon the provider the user chose
        // over something the retry usually fixes.
        assertFalse(decide(exhausted = spent(true, 0, 3)))
        assertFalse(decide(exhausted = spent(true, 2, 3)))
    }

    @Test
    fun `a non-transient failure never counts as budget-spent`() {
        assertFalse(spent(isTransient = false, used = 99, max = 3))
        assertFalse(decide(exhausted = spent(false, 99, 3)))
    }

    @Test
    fun `budget spent uses greater-or-equal, not equality`() {
        // The class is re-derived per failure, so a 502 burst that ends as a
        // dropped socket can LOWER the ceiling below the count already spent.
        // `==` would miss that and strand the turn — the exact failure this
        // policy removes.
        assertTrue(spent(true, used = 5, max = 2))
    }

    // ── the pre-existing signals still work ─────────────────────────────────

    @Test
    fun `rate limit, content filter, 5xx ProviderError and empty response fall back immediately`() {
        // These fire WITHOUT spending retries: waiting cannot help a 429 on
        // someone else's quota, a deterministic moderation verdict, or a
        // provider that closed the stream having sent nothing.
        assertTrue(decide(rateLimit = true))
        assertTrue(decide(contentFilter = true))
        assertTrue(decide(http5xx = true))
        assertTrue(decide(empty = true))
    }

    @Test
    fun `strategy always falls back on anything`() {
        assertTrue(decide(always = true))
    }

    @Test
    fun `a clean non-retryable failure with no signal does not fall back`() {
        assertFalse(decide())
    }

    // ── NO_RESPONSE budget shape ────────────────────────────────────────────

    @Test
    fun `no-response gets a small budget because each attempt costs 120 seconds`() {
        val k = TransientRetryBudget.Kind.NO_RESPONSE
        // 2 attempts ≈ 4 minutes worst case. CONNECTION's 4 would be 8, during
        // which the user sees nothing happening.
        assertEquals(2, TransientRetryBudget.maxAttempts(k))
        assertTrue(
            "the watchdog budget must stay below the plain-socket one",
            TransientRetryBudget.maxAttempts(k) <
                TransientRetryBudget.maxAttempts(TransientRetryBudget.Kind.CONNECTION),
        )
        // No extra sleep: the 120s wait already happened before the error existed.
        assertEquals(0, TransientRetryBudget.delaySecForAttempt(k, 0))
        assertEquals(0, TransientRetryBudget.delaySecForAttempt(k, 1))
        // A socket that swallowed a request and went silent is not reusable.
        assertTrue(TransientRetryBudget.evictsPool(k))
        assertFalse(TransientRetryBudget.awaitsConnectivity(k))
    }

    @Test
    fun `the watchdog marker is shared with StaleConnectionPolicy`() {
        // Two independent definitions of "server went silent" would drift, and
        // then eviction and classification would disagree about the same error.
        val msg = "no response from server (120s) — check network/proxy"
        assertTrue(StaleConnectionPolicy.isStaleConnection(msg))
        assertEquals(TransientRetryBudget.Kind.NO_RESPONSE, TransientRetryBudget.classify(msg))
    }
}
