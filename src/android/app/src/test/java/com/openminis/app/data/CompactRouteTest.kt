package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-route] Observed live: `Compaction failed: Quota exceeded: 预扣费…`
 * on kimi-k3 after a `Rate limited` turn. Compaction was a single call on the
 * bound model, so a refusal left the session both too big to send and unable
 * to shrink. These tests pin the escalation ladder that replaced it.
 */
class CompactRouteTest {

    // Real gateway strings seen in the wild (new-api / one-api relays).
    private val quotaCn = "Quota exceeded: 预扣费额度失败 token quota is not enough, token remain quota: \$0.219130, need quota: \$1.040466"
    private val quotaEn = "Quota exceeded: pre-consume quota failed, insufficient_user_quota"
    private val rateLimited = "Rate limited — please try again later"

    @Test
    fun `recognises quota refusals in both languages`() {
        assertTrue(CompactRoute.isQuota(quotaCn))
        assertTrue(CompactRoute.isQuota(quotaEn))
        assertTrue(CompactRoute.isQuota("insufficient balance"))
        assertFalse(CompactRoute.isQuota("Invalid API key"))
    }

    @Test
    fun `recognises rate limits`() {
        assertTrue(CompactRoute.isRateLimit(rateLimited))
        assertTrue(CompactRoute.isRateLimit("HTTP 429 too many requests"))
        assertFalse(CompactRoute.isRateLimit("Provider error: [400] bad request"))
    }

    @Test
    fun `quota first asks for a smaller summary on the same model`() {
        // Relays pre-charge on max_tokens, so a shorter answer is often
        // affordable with the same key — cheaper than switching models.
        val step = CompactRoute.next(quotaCn, currentMaxTokens = 8192, shrinkStepsUsed = 0, nextProviderIndex = 0)
        assertEquals(CompactRoute.Step.RetrySmaller(4096), step)
    }

    @Test
    fun `quota shrink is bounded then moves on`() {
        val step = CompactRoute.next(
            quotaCn, currentMaxTokens = 2048,
            shrinkStepsUsed = CompactRoute.MAX_SHRINK_STEPS, nextProviderIndex = 2,
        )
        assertEquals(CompactRoute.Step.NextProvider(2), step)
    }

    @Test
    fun `rate limit skips the shrink step entirely`() {
        // A per-request-count limit does not care how many tokens we ask for,
        // so retrying smaller on the same model is pure waste.
        val step = CompactRoute.next(rateLimited, currentMaxTokens = 8192, shrinkStepsUsed = 0, nextProviderIndex = 1)
        assertEquals(CompactRoute.Step.NextProvider(1), step)
    }

    @Test
    fun `exhausted providers fall back to the local digest`() {
        for (msg in listOf(quotaCn, rateLimited)) {
            val step = CompactRoute.next(
                msg, currentMaxTokens = CompactRoute.MIN_MAX_TOKENS,
                shrinkStepsUsed = CompactRoute.MAX_SHRINK_STEPS, nextProviderIndex = null,
            )
            assertEquals(CompactRoute.Step.LocalDigest, step)
        }
    }

    @Test
    fun `quota with no smaller budget left goes local when no provider remains`() {
        val step = CompactRoute.next(
            quotaCn, currentMaxTokens = CompactRoute.MIN_MAX_TOKENS,
            shrinkStepsUsed = 0, nextProviderIndex = null,
        )
        assertEquals(CompactRoute.Step.LocalDigest, step)
    }

    @Test
    fun `unrelated failures are surfaced not routed`() {
        // A 400 means the request is wrong; retrying it anywhere is noise, and
        // silently switching models would hide a real bug.
        val step = CompactRoute.next(
            "Provider error: [400] messages: invalid role",
            currentMaxTokens = 8192, shrinkStepsUsed = 0, nextProviderIndex = 0,
        )
        assertTrue(step is CompactRoute.Step.Surface)
    }

    @Test
    fun `shrink halves and floors`() {
        assertEquals(4096, CompactRoute.shrink(8192))
        assertEquals(CompactRoute.MIN_MAX_TOKENS, CompactRoute.shrink(2048))
        assertNull(CompactRoute.shrink(CompactRoute.MIN_MAX_TOKENS))
        assertNull(CompactRoute.shrink(1500))
    }

    @Test
    fun `a message that is both rate limited and quota takes the rate-limit path`() {
        // Some relays put both in one body. Size-shrinking cannot clear a rate
        // limit, so the stricter constraint wins.
        val both = "429 rate limit reached; token quota is not enough"
        val step = CompactRoute.next(both, currentMaxTokens = 8192, shrinkStepsUsed = 0, nextProviderIndex = 3)
        assertEquals(CompactRoute.Step.NextProvider(3), step)
    }
}
