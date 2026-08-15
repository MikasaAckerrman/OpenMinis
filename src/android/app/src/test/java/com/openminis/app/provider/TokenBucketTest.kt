package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [429-concurrent-sessions] Token-bucket arithmetic that paces concurrent
 * sessions off one provider key. Deterministic — the clock is a parameter,
 * never `System.currentTimeMillis()`. See [TokenBucket].
 */
class TokenBucketTest {

    @Test
    fun `full bucket admits a burst then paces at refill rate`() {
        val b = TokenBucket(capacity = 5.0, refillPerSec = 1.0, initialTokens = 5.0, initialMs = 0L)
        repeat(5) { assertEquals("burst token $it should be immediate", 0L, b.tryAcquire(0L)) }
        // 6th: empty bucket, 1 token/sec → ~1000ms
        assertTrue(b.tryAcquire(0L) in 900L..1000L)
    }

    @Test
    fun `tokens accrue over elapsed time`() {
        val b = TokenBucket(capacity = 5.0, refillPerSec = 2.0, initialTokens = 0.0, initialMs = 0L)
        assertTrue("empty bucket waits", b.tryAcquire(0L) > 0L)
        assertEquals("after 500ms at 2/s one token is available", 0L, b.tryAcquire(500L))
    }

    @Test
    fun `refill never exceeds capacity`() {
        val b = TokenBucket(capacity = 3.0, refillPerSec = 100.0, initialTokens = 0.0, initialMs = 0L)
        b.refill(10_000L)
        assertEquals(3.0, b.tokens, 1e-9)
    }

    @Test
    fun `zero refill rate disables the bucket`() {
        val b = TokenBucket(capacity = 1.0, refillPerSec = 0.0, initialTokens = 0.0, initialMs = 0L)
        assertEquals(Long.MAX_VALUE, b.tryAcquire(5_000L))
    }

    @Test
    fun `clock going backwards is a no-op`() {
        val b = TokenBucket(capacity = 5.0, refillPerSec = 1.0, initialTokens = 2.0, initialMs = 1000L)
        b.refill(500L)
        assertEquals(2.0, b.tokens, 1e-9)
    }

    @Test
    fun `fractional accrual is precise`() {
        val b = TokenBucket(capacity = 10.0, refillPerSec = 4.0, initialTokens = 0.0, initialMs = 0L)
        // 0.25s * 4/s = exactly one token
        assertEquals(0L, b.tryAcquire(250L))
    }
}
