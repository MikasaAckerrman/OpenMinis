package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-gateway-downtime-as-transient] Locks the classification that decides
 * whether a 401/403 means "your key is bad" (non-retryable) or "the gateway
 * itself is temporarily down" (retryable). Every downtime body here was
 * captured from a live relay that answered 401 with the "astral" wording while
 * a valid key was used.
 */
class GatewayDowntimeDetectionTest {

    // -- Real provider-down bodies that MUST be reclassified --

    @Test
    fun `russian astral wording on a valid key is a downtime failure`() {
        // Captured 2026-09-15 from a syntro-class relay with a CORRECT key.
        // Used to surface as "Invalid API key".
        val body = """{"type":"error","error":{"type":"authentication_error",""" +
            """"message":"Нейросеть слишком глубоко задумалась о смысле бытия и """ +
            """временно вышла в астрал. Дайте кремниевому мозгу 30 секунд на перекур и отправьте снова."}}"""
        assertTrue(GatewayDowntimeDetection.isDowntimeFailure(body))
    }

    @Test
    fun `english temporarily unavailable is a downtime failure`() {
        assertTrue(
            GatewayDowntimeDetection.isDowntimeFailure(
                """{"error":{"message":"The service is temporarily unavailable, please retry"}}""",
            ),
        )
    }

    @Test
    fun `chinese upstream-busy wording is a downtime failure`() {
        assertTrue(GatewayDowntimeDetection.isDowntimeFailure("""{"message":"服务器繁忙，请稍后重试"}"""))
    }

    @Test
    fun `detection is case insensitive`() {
        assertTrue(GatewayDowntimeDetection.isDowntimeFailure("""{"message":"Service UNAVAILABLE"}"""))
    }

    // -- Genuine key errors that must NOT be softened into "retry later" --

    @Test
    fun `genuine invalid key is not a downtime failure`() {
        // Same gateway, deliberately-wrong key: this is the case where
        // "Invalid API key" is the CORRECT verdict and must not become retryable.
        assertFalse(GatewayDowntimeDetection.isDowntimeFailure(
            """{"type":"error","error":{"type":"authentication_error","message":"Invalid API key"}}""",
        ))
    }

    @Test
    fun `key invalid phrasing wins even when downtime words also appear`() {
        // AND-NOT guard: a body that both says the key is bad and mentions
        // "temporarily" must stay a credential error (actionable), never "wait".
        assertFalse(GatewayDowntimeDetection.isDowntimeFailure(
            """{"error":"your api key is invalid; a temporarily unrelated note"}""",
        ))
    }

    @Test
    fun `empty body is not a downtime failure`() {
        assertFalse(GatewayDowntimeDetection.isDowntimeFailure(""))
    }

    // -- Message extraction --

    @Test
    fun `describe extracts the gateway message not raw json`() {
        val body = """{"error":{"message":"сервис временно недоступен"}}"""
        assertEquals("сервис временно недоступен", GatewayDowntimeDetection.describe(body))
    }

    @Test
    fun `describe falls back to the body without a message field`() {
        val body = """{"code":401,"detail":"nope"}"""
        assertEquals(body, GatewayDowntimeDetection.describe(body))
    }
}
