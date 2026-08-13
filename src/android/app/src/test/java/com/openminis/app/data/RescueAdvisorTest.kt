package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-rescue] The advisor's job is to recognise "this failed because
 * the session is too big" even when the provider does NOT say so — the
 * reported symptom was "no response server" on a session that was merely
 * oversized. It must stay quiet on genuine network failures in small
 * sessions, otherwise the hint becomes noise.
 */
class RescueAdvisorTest {

    @Test
    fun `explicit size errors suggest rescue at any size`() {
        val messages = listOf(
            "Request too large for gpt-4o",
            "This model's maximum context length is 128000 tokens",
            "prompt is too long: 210000 tokens > 200000 maximum",
            "HTTP 413 Payload Too Large",
        )
        for (m in messages) {
            assertTrue(m, RescueAdvisor.shouldSuggestRescue(m, contextTokens = 0, contextWindow = 0))
        }
    }

    @Test
    fun `vague transport failure on a large session suggests rescue`() {
        // 150K of a 200K window = 75% — past the 60% line.
        assertTrue(
            RescueAdvisor.shouldSuggestRescue(
                "no response from server (30s) — check network/proxy",
                contextTokens = 150_000,
                contextWindow = 200_000,
            )
        )
        assertTrue(
            RescueAdvisor.shouldSuggestRescue(
                "unexpected end of stream",
                contextTokens = 150_000,
                contextWindow = 200_000,
            )
        )
    }

    @Test
    fun `vague transport failure on a small session stays quiet`() {
        assertFalse(
            RescueAdvisor.shouldSuggestRescue(
                "no response from server (30s) — check network/proxy",
                contextTokens = 4_000,
                contextWindow = 200_000,
            )
        )
    }

    @Test
    fun `unknown context size with a vague error stays quiet`() {
        assertFalse(
            RescueAdvisor.shouldSuggestRescue(
                "connection reset",
                contextTokens = 0,
                contextWindow = 0,
            )
        )
    }

    @Test
    fun `unrelated errors never suggest rescue`() {
        val messages = listOf(
            "401 Unauthorized: invalid api key",
            "model not found: gpt-9",
            "content blocked by moderation",
        )
        for (m in messages) {
            assertFalse(
                m,
                RescueAdvisor.shouldSuggestRescue(m, contextTokens = 190_000, contextWindow = 200_000),
            )
        }
    }

    @Test
    fun `boundary at the large-context fraction is inclusive`() {
        val window = 100_000
        val atLine = (window * RescueAdvisor.LARGE_CONTEXT_FRACTION).toInt()
        assertTrue(
            RescueAdvisor.shouldSuggestRescue("connection closed", atLine, window)
        )
        assertFalse(
            RescueAdvisor.shouldSuggestRescue("connection closed", atLine - 1_000, window)
        )
    }
}
