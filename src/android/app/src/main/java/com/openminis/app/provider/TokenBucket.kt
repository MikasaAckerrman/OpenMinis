package com.openminis.app.provider

/**
 * Pure token-bucket rate limiter arithmetic (no coroutines, no clock of its
 * own) so it can be unit-tested deterministically. [LlmDispatchGate] wraps a
 * bucket per provider-key and feeds it a real clock.
 *
 * ## Why a token bucket (not a fixed window)
 *
 * Provider RPM limits are a sliding budget: 60 requests/min really means
 * "don't burst faster than ~1/sec on average, but a short burst is fine". A
 * token bucket models exactly that — [capacity] tokens accrue at [refillPerSec]
 * and each request spends one. When many sessions fire at once they drain the
 * bucket and then pace themselves at the refill rate instead of all slamming
 * the key in the same 100ms and tripping HTTP 429. A fixed-window counter, by
 * contrast, lets the whole minute's budget fire in the first instant of each
 * window (the classic double-burst-at-the-boundary problem).
 *
 * The bucket is a plain value type: callers hold the mutable [tokens] /
 * [lastRefillMs] and pass them back in, or use the convenience mutating
 * wrapper in [LlmDispatchGate]. Kept pure here on purpose — the moment this
 * owns a clock or a lock it stops being unit-testable without faking time.
 */
class TokenBucket(
    val capacity: Double,
    val refillPerSec: Double,
    initialTokens: Double = capacity,
    initialMs: Long = 0L,
) {
    var tokens: Double = initialTokens
        private set
    var lastRefillMs: Long = initialMs
        private set

    /** Accrue tokens for elapsed wall-clock time, capped at [capacity]. */
    fun refill(nowMs: Long) {
        if (nowMs <= lastRefillMs) return
        val elapsedSec = (nowMs - lastRefillMs) / 1000.0
        tokens = (tokens + elapsedSec * refillPerSec).coerceAtMost(capacity)
        lastRefillMs = nowMs
    }

    /**
     * Try to spend one token at [nowMs].
     *
     * @return 0 when a token was available (spent — caller proceeds now), or a
     *         positive wait in ms until the next token accrues. Never blocks.
     */
    fun tryAcquire(nowMs: Long): Long {
        refill(nowMs)
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0L
        }
        // Time for (1 - tokens) more tokens to accrue.
        val deficit = 1.0 - tokens
        if (refillPerSec <= 0.0) return Long.MAX_VALUE // never refills — disabled
        val waitSec = deficit / refillPerSec
        return kotlin.math.ceil(waitSec * 1000.0).toLong().coerceAtLeast(1L)
    }
}
