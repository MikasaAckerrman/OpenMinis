package com.openminis.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterDetectionTest {

    /** The exact body observed on AgentRouter / gpt-5.6-sol, 2026-08-10. */
    private val liveBody =
        """{"error":{"message":"sensitive words detected (request id: 20260810184932592602059qxpxI8x8MWVm)"}}"""

    @Test
    fun `recognises the live AgentRouter rejection`() {
        assertTrue(ContentFilterDetection.isContentFilterRejection(liveBody))
    }

    @Test
    fun `recognises chinese phrasings`() {
        assertTrue(ContentFilterDetection.isContentFilterRejection("""{"message":"请求包含敏感词"}"""))
        assertTrue(ContentFilterDetection.isContentFilterRejection("""{"message":"内容审核未通过"}"""))
    }

    @Test
    fun `recognises openai style content_filter`() {
        assertTrue(
            ContentFilterDetection.isContentFilterRejection(
                """{"error":{"code":"content_filter","message":"blocked"}}""",
            ),
        )
    }

    @Test
    fun `recognises live 400 content blocked response`() {
        val body =
            """{"error":{"code":"content-blocked","message":"content-blocked (request id: 202608160829432459525697db9zJlZhCgwN)"}}"""
        assertTrue(ContentFilterDetection.isContentFilterRejection(body))
        assertTrue(ContentFilterDetection.describe(body).contains("content-blocked"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(ContentFilterDetection.isContentFilterRejection("SENSITIVE WORDS DETECTED"))
    }

    @Test
    fun `a real server fault is not a filter rejection`() {
        // Must stay TransientError: retrying these genuinely helps.
        assertFalse(ContentFilterDetection.isContentFilterRejection("""{"error":{"message":"internal server error"}}"""))
        assertFalse(ContentFilterDetection.isContentFilterRejection("""{"error":{"message":"upstream timeout"}}"""))
        assertFalse(ContentFilterDetection.isContentFilterRejection("502 Bad Gateway"))
        assertFalse(ContentFilterDetection.isContentFilterRejection(""))
    }

    @Test
    fun `a quota rejection is not mistaken for moderation`() {
        // The two detectors must not overlap: quota is 403 + retry-never,
        // moderation is 500 + fallback. Confusing them would send the user to
        // the wrong fix.
        val quota =
            """{"error":{"code":"pre_consume_token_quota_failed","message":"token quota is not enough"}}"""
        assertFalse(ContentFilterDetection.isContentFilterRejection(quota))
        assertTrue(QuotaErrorDetection.isQuotaFailure(quota))
        assertFalse(QuotaErrorDetection.isQuotaFailure(liveBody))
    }

    @Test
    fun `describe explains whose filter fired and quotes the gateway`() {
        val text = ContentFilterDetection.describe(liveBody)
        assertTrue("must name the gateway", text.contains("gateway", ignoreCase = true))
        assertTrue("must carry the gateway's own message", text.contains("sensitive words detected"))
        assertTrue("must say the model is not the blocker", text.contains("another provider"))
    }

    @Test
    fun `describe survives a non-json body`() {
        val text = ContentFilterDetection.describe("<html>sensitive words detected</html>")
        assertTrue(text.contains("sensitive words detected"))
    }

    @Test
    fun `describe survives a truncated body`() {
        val text = ContentFilterDetection.describe("""{"error":{"message":"sensitive words dete""")
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `describe unescapes quotes in the gateway message`() {
        val text = ContentFilterDetection.describe(
            """{"error":{"message":"sensitive words detected: \"banned\""}}""",
        )
        assertTrue(text.contains("banned"))
    }
}
