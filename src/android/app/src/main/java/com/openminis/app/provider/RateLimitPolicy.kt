package com.openminis.app.provider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure, side-effect-free policy for handling HTTP 429 (rate limited) responses
 * from LLM providers.
 *
 * ## Why this exists
 *
 * When many chat sessions stream concurrently they all hit the SAME API key /
 * endpoint, and the provider counts requests-per-minute (RPM) and
 * tokens-per-minute (TPM) against that key. The instant the window is exceeded
 * it answers HTTP 429 — this is a PROVIDER-side counter, unrelated to the
 * device's bandwidth. A 429 usually carries a `Retry-After` header telling us
 * exactly how long to wait; before this class that header was parsed nowhere
 * (grep proved it), so [com.openminis.app.data.model.LLMError.RateLimited] was
 * a bare signal and every session immediately re-tried or fell back in
 * lock-step — a thundering herd that produced MORE 429s on the fallback key.
 *
 * This object is deliberately pure so it can be unit-tested without a network,
 * a device, or coroutines: [LlmDispatchGate] owns the stateful throttling and
 * delegates all arithmetic here.
 */
object RateLimitPolicy {

    /**
     * Local backoff schedule (ms) used ONLY when a 429 arrives without a
     * usable `Retry-After` header. Mirrors the transient-error schedule in
     * ChatViewModel (1s → 2s → 4s) so behaviour is consistent across error
     * families.
     */
    val BACKOFF_MS: LongArray = longArrayOf(1_000L, 2_000L, 4_000L)

    /**
     * Hard ceiling on any single wait. A hostile or misconfigured relay can
     * answer `Retry-After: 3600`; blocking a user-visible turn for an hour is
     * never the right call — after the ceiling we surface the error and let the
     * model-group fallback take over. 30s is long enough to clear a
     * per-minute window without freezing the UI.
     */
    const val MAX_WAIT_MS: Long = 30_000L

    /**
     * Parse an HTTP `Retry-After` header into a wait in milliseconds.
     *
     * The header comes in two RFC-7231 shapes:
     *  - delta-seconds: `Retry-After: 120`
     *  - HTTP-date:     `Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`
     *
     * @param header the raw header value, or null when absent.
     * @param nowMs  injected clock for deterministic tests.
     * @return a non-negative wait in ms, or null when the header is
     *         absent/unparseable (caller then uses [BACKOFF_MS]).
     */
    fun parseRetryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // delta-seconds — the common case. Fractional/negative values are
        // rejected to fall through to the date parse, then to null.
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return 0L
            return seconds * 1_000L
        }

        // HTTP-date. Servers may emit any RFC-1123-ish spelling; a parse
        // failure here is non-fatal — we simply have no hint.
        return try {
            val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            val deltaMs = target.toInstant().toEpochMilli() - nowMs
            if (deltaMs < 0) 0L else deltaMs
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Effective wait before the next attempt after a 429.
     *
     * Prefers the server's [retryAfterMs] hint (it knows its own window);
     * falls back to the local [BACKOFF_MS] schedule indexed by [attempt].
     * Always clamped to [MAX_WAIT_MS]. A negative or zero result means
     * "retry immediately".
     *
     * @param attempt      zero-based retry attempt (0 = first retry).
     * @param retryAfterMs server hint from [parseRetryAfterMs], or null.
     */
    fun waitMsForAttempt(attempt: Int, retryAfterMs: Long?): Long {
        val base = when {
            retryAfterMs != null -> retryAfterMs
            attempt < 0 -> BACKOFF_MS.first()
            attempt < BACKOFF_MS.size -> BACKOFF_MS[attempt]
            else -> BACKOFF_MS.last()
        }
        return base.coerceIn(0L, MAX_WAIT_MS)
    }
}
