package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * [429-concurrent-sessions] Pins the pure 429 backoff policy: parse the
 * provider's `Retry-After` (the thing that was ignored before), prefer it over
 * the local schedule, and clamp everything to a UI-safe ceiling. See
 * [RateLimitPolicy].
 */
class RateLimitPolicyTest {

    // -- Retry-After: delta-seconds --

    @Test
    fun `parses delta-seconds header`() {
        assertEquals(120_000L, RateLimitPolicy.parseRetryAfterMs("120"))
        assertEquals(0L, RateLimitPolicy.parseRetryAfterMs("0"))
    }

    @Test
    fun `negative delta clamps to zero`() {
        assertEquals(0L, RateLimitPolicy.parseRetryAfterMs("-5"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(30_000L, RateLimitPolicy.parseRetryAfterMs("  30  "))
    }

    // -- Retry-After: absent / unparseable → null so caller uses local backoff --

    @Test
    fun `null and blank and garbage return null`() {
        assertNull(RateLimitPolicy.parseRetryAfterMs(null))
        assertNull(RateLimitPolicy.parseRetryAfterMs(""))
        assertNull(RateLimitPolicy.parseRetryAfterMs("soon"))
        // fractional is neither a valid delta-seconds nor an HTTP-date
        assertNull(RateLimitPolicy.parseRetryAfterMs("1.5"))
    }

    // -- Retry-After: HTTP-date --

    @Test
    fun `parses future HTTP-date relative to injected clock`() {
        val now = 1_000_000_000_000L
        val header = ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(now + 60_000L), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val parsed = RateLimitPolicy.parseRetryAfterMs(header, now)
        assertTrue("expected ~60000, got $parsed", parsed != null && parsed in 59_000L..61_000L)
    }

    @Test
    fun `past HTTP-date yields zero wait`() {
        val now = 1_000_000_000_000L
        val header = ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(now - 60_000L), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(0L, RateLimitPolicy.parseRetryAfterMs(header, now))
    }

    // -- waitMsForAttempt: local schedule when no server hint --

    @Test
    fun `local backoff follows 1-2-4 schedule and saturates`() {
        assertEquals(1_000L, RateLimitPolicy.waitMsForAttempt(0, null))
        assertEquals(2_000L, RateLimitPolicy.waitMsForAttempt(1, null))
        assertEquals(4_000L, RateLimitPolicy.waitMsForAttempt(2, null))
        assertEquals(4_000L, RateLimitPolicy.waitMsForAttempt(9, null))
        assertEquals(1_000L, RateLimitPolicy.waitMsForAttempt(-1, null))
    }

    // -- waitMsForAttempt: server hint wins, clamped to ceiling --

    @Test
    fun `server hint overrides schedule and is clamped`() {
        assertEquals(5_000L, RateLimitPolicy.waitMsForAttempt(0, 5_000L))
        assertEquals(RateLimitPolicy.MAX_WAIT_MS, RateLimitPolicy.waitMsForAttempt(0, 3_600_000L))
        assertEquals(0L, RateLimitPolicy.waitMsForAttempt(0, 0L))
    }
}
