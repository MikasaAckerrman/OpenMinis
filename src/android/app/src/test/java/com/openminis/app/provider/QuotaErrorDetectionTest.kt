package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-quota-mislabeled-as-invalid-key] Locks the classification that decides
 * whether the user is told "your key is bad" or "you're out of credit".
 *
 * Every quota body here was captured from a live gateway response.
 */
class QuotaErrorDetectionTest {

    // -- Real bodies that MUST be recognised as quota problems --

    @Test
    fun `pre_consume_token_quota_failed is a quota failure`() {
        // Captured 2026-08-10 from agentrouter.org with a valid, funded key.
        // This exact body used to be reported as "Invalid API key".
        val body = """{"error":{"message":"token quota is not enough, token remain quota: ＄0.219130, need quota: ＄1.040466 (request id: 202608100305014760837905ghdwg9D2rZr2)","type":"new_api_error","param":"","code":"pre_consume_token_quota_failed"}}"""
        assertTrue(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `insufficient_user_quota english wording is a quota failure`() {
        val body = """{"error":{"message":"pre-consume quota failed, user quota: ＄0.537002, need quota: ＄1.036590","type":"new_api_error","code":"insufficient_user_quota"}}"""
        assertTrue(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `chinese pre-charge wording is a quota failure`() {
        val body = """{"error":{"message":"预扣费额度失败, 用户剩余额度: ＄0.028382, 需要预扣费额度: ＄0.200000","code":"insufficient_user_quota"}}"""
        assertTrue(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `chinese remaining-balance wording without a known code is a quota failure`() {
        // Same gateway family, different endpoint: only the Chinese phrase
        // identifies it — there is no recognisable error code.
        val body = """{"message":"用户额度不足, 剩余额度 ＄0.01","success":false}"""
        assertTrue(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `openai insufficient_quota is a quota failure`() {
        val body = """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","code":"insufficient_quota"}}"""
        assertTrue(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `detection is case insensitive`() {
        assertTrue(QuotaErrorDetection.isQuotaFailure("""{"code":"INSUFFICIENT_USER_QUOTA"}"""))
    }

    // -- Real auth failures that must NOT be reclassified --

    @Test
    fun `genuine invalid key is not a quota failure`() {
        // Captured from co.agentrouter.org with a wrong key — the case where
        // "Invalid API key" is the CORRECT message.
        val body = """{"code":401,"msg":"Invalid API Key!","data":null}"""
        assertFalse(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `unauthorized client user-agent gate is not a quota failure`() {
        val body = """{"error":{"message":"unauthorized client detected, contact support"},"type":"unauthorized_client_error"}"""
        assertFalse(QuotaErrorDetection.isQuotaFailure(body))
    }

    @Test
    fun `empty body is not a quota failure`() {
        assertFalse(QuotaErrorDetection.isQuotaFailure(""))
    }

    // -- Message extraction --

    @Test
    fun `describe extracts the gateway message instead of raw json`() {
        val body = """{"error":{"message":"token quota is not enough, token remain quota: ＄0.219130, need quota: ＄1.040466","code":"pre_consume_token_quota_failed"}}"""
        assertEquals(
            "token quota is not enough, token remain quota: ＄0.219130, need quota: ＄1.040466",
            QuotaErrorDetection.describe(body),
        )
    }

    @Test
    fun `describe unescapes quotes and folds newlines`() {
        val body = """{"error":{"message":"quota for \"team\" plan\nis exhausted"}}"""
        assertEquals("quota for \"team\" plan is exhausted", QuotaErrorDetection.describe(body))
    }

    @Test
    fun `describe falls back to the body when there is no message field`() {
        val body = """{"code":403,"detail":"nope"}"""
        assertEquals(body, QuotaErrorDetection.describe(body))
    }

    @Test
    fun `describe survives a truncated body`() {
        // Error paths can hand us a body cut mid-string; a parser would throw.
        val body = """{"error":{"message":"token quota is not eno"""
        assertEquals("token quota is not eno", QuotaErrorDetection.describe(body))
    }

    @Test
    fun `describe respects the limit`() {
        val body = """{"error":{"message":"${"x".repeat(900)}"}}"""
        assertEquals(500, QuotaErrorDetection.describe(body).length)
    }

    @Test
    fun `describe on non-json body returns the truncated body`() {
        val body = "<html>502 Bad Gateway</html>"
        assertEquals(body, QuotaErrorDetection.describe(body))
    }
}
