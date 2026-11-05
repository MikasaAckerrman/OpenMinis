package com.openminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [B14-secret-redaction] Pure-JVM contract of the AppLogger sink mask:
 * high-confidence credentials are masked, normal diagnostic text survives
 * verbatim. Every false positive here would mangle a real log line, so the
 * negative cases are as load-bearing as the positive ones.
 */
class SecretRedactionTest {

    @Test
    fun `bearer masked, original scheme case kept`() {
        val line = "Authorization: Bearer eyJhbGci0iJIUzI1NiJ9.payload-x.abc123"
        assertEquals("Authorization: Bearer ***", SecretRedaction.mask(line))
        val lower = "curl -H 'bearer sk-ignored-0000000000000000000000'"
        assertEquals("curl -H 'bearer ***'", SecretRedaction.mask(lower))
    }

    @Test
    fun `english phrases around bearer survive (no digit, short)`() {
        assertEquals(
            "bearer token rotation happens next week",
            SecretRedaction.mask("bearer token rotation happens next week"),
        )
        assertEquals(
            "Bearer authentication is required for this endpoint",
            SecretRedaction.mask("Bearer authentication is required for this endpoint"),
        )
    }

    @Test
    fun `openai-style sk key masked`() {
        assertEquals("***", SecretRedaction.mask("sk-abcdef0123456789abcdef"))
        // anthropic spelling rides the same prefix
        assertEquals(
            "key=***",
            SecretRedaction.mask("key=sk-ant-api03-abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `words merely containing sk- survive (lookbehind)`() {
        assertEquals(
            "task-abcdefghij0123456789 finished",
            SecretRedaction.mask("task-abcdefghij0123456789 finished"),
        )
    }

    @Test
    fun `github token family masked`() {
        assertEquals("***", SecretRedaction.mask("ghp_0123456789abcdefghijklmnopqrstuv"))
        assertEquals("***", SecretRedaction.mask("gho_0123456789abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `x-minis-token value masked, key kept in both spellings`() {
        assertEquals(
            "x-minis-token: ***",
            SecretRedaction.mask("x-minis-token: abc123def456"),
        )
        assertEquals(
            "X-Minis-Token=***",
            SecretRedaction.mask("X-Minis-Token=abc123def456"),
        )
    }

    @Test
    fun `normal diagnostic line untouched`() {
        val line =
            "session queued_1234567890 loaded, 42 rows, path=/data/logs, mode=group"
        assertEquals(line, SecretRedaction.mask(line))
    }

    @Test
    fun `composed line - both secrets gone, structure intact`() {
        val line = "POST /v1/chat Authorization: Bearer abc123def456ghi789 len=sk-abcdef0123456789abcdef"
        assertEquals("POST /v1/chat Authorization: Bearer *** len=***", SecretRedaction.mask(line))
    }

    @Test
    fun `short and empty inputs are fast-pathed`() {
        assertEquals("", SecretRedaction.mask(""))
        assertEquals("abc", SecretRedaction.mask("abc"))
    }
}
