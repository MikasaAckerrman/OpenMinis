package com.openminis.app.engine

import com.openminis.app.data.model.LLMError
import java.io.IOException

/**
 * [B42-network-retry] Pure policy for the engine loop's in-round network
 * auto-retry (the legacy loop's [com.openminis.app.data.TransientRetryBudget]
 * sibling — same problem, engine-shaped answer).
 *
 * ## Why this exists
 *
 * B40 made transport failures HONEST on the engine path: the gateway maps
 * them to a terminal `AgentEvent.Error`, the driver throws, and the turn
 * waits for the user's manual Resume click. Honest, but a one-second Wi-Fi
 * blink would freeze a night run the moment the socket died — the legacy
 * loop always auto-retried (1s/2s/4s) and nobody had to babysit it. The
 * engine path gets the same autonomy INSIDE the round: a transient stream
 * failure before any tool was dispatched re-opens the gateway stream after
 * a short backoff, with a visible status, instead of tearing the turn down.
 *
 * ## The dispatch-safety invariant
 *
 * A retry re-runs the model request, so the retried round may re-emit the
 * tool calls of the failed attempt. With speculative dispatch ([Опора 1])
 * a call whose args JSON closed has ALREADY launched into execution — a
 * retry after that would execute it twice (double file_write, double
 * spawn, double spend). [shouldRetry] therefore refuses when
 * `toolsDispatched` is true: a round with launched tools surfaces the
 * failure the old way (terminal Error, manual Resume). Retry covers the
 * request-start class — connection refused, DNS miss, idle-socket read
 * timeout, provider 5xx — which is where the field incidents live.
 *
 * Deliberately NOT here: provider switching (B40 — the user switches
 * providers by hand), rate-limit Retry-After parsing (the legacy dispatch
 * gate owns it), NetworkJournal (app-side diagnostics, driver's seam).
 */
object NetworkRetryPolicy {

    /** Automatic attempts per round before the failure surfaces (3 retries). */
    const val MAX_RETRIES = 3

    /**
     * Backoff schedule (ms), attempt 1 → 1.5s, 2 → 3s, 3 → 6s — the
     * exponential ladder the B42 directive pinned. A mobile radio needs
     * about a second to re-associate; the first wait covers a brief blink,
     * the last one a parked-radio reattach.
     */
    val BACKOFF_MS: LongArray = longArrayOf(1_500L, 3_000L, 6_000L)

    /**
     * Wait before retry [attempt] (1-based). Beyond the schedule the last
     * rung repeats; [shouldRetry] is the gate that stops the loop, so this
     * clamp only guards direct callers.
     */
    fun waitMsForAttempt(attempt: Int): Long = when {
        attempt <= 1 -> BACKOFF_MS.first()
        attempt - 1 < BACKOFF_MS.size -> BACKOFF_MS[attempt - 1]
        else -> BACKOFF_MS.last()
    }

    /**
     * The round-retry gate. All three conditions are load-bearing:
     *  - [transient] — only the transport class auto-retries; a content
     *    filter rejection or an auth error can never succeed on re-send;
     *  - `!toolsDispatched` — the double-execution invariant above;
     *  - `attempt < MAX_RETRIES` — three retries, then the failure
     *    surfaces to the user with the Resume button.
     */
    fun shouldRetry(transient: Boolean, attempt: Int, toolsDispatched: Boolean): Boolean =
        transient && !toolsDispatched && attempt in 0 until MAX_RETRIES

    /**
     * Classify a gateway-stream failure cause. Type-based first (the
     * provider layer already wraps transport faults into
     * [LLMError.NetworkError]/[LLMError.TransientError]/[LLMError.RateLimited],
     * and raw [IOException] covers SocketTimeout/UnknownHost/Connect);
     * message tokens as the fallback for errors that arrive bare.
     */
    fun isTransientCause(cause: Throwable?): Boolean = when {
        cause == null -> false
        cause is LLMError.RateLimited -> true
        cause is LLMError.NetworkError -> true
        cause is LLMError.TransientError -> true
        cause is IOException -> true
        else -> isTransientMessage(cause.message)
    }

    /**
     * Message-token fallback. Phrases are the observed transport classes
     * (the vc154 live incident was exactly "stream idle timeout"); HTTP
     * statuses use the SAME anchored shapes the legacy path matches —
     * `[5\d{2}]` / `HTTP 5\d{2}` / `[429]` / `HTTP 429` — because a bare
     * digit regex once matched request-id digits and retried deterministic
     * rejections three times on a provider that could never serve them.
     */
    fun isTransientMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        if (m.isEmpty()) return false
        if (TRANSIENT_PHRASES.any { it in m }) return true
        return HTTP_STATUS_REGEX.containsMatchIn(m)
    }

    private val TRANSIENT_PHRASES = listOf(
        "stream idle timeout",
        "timed out",
        "timeout",
        "unable to resolve host",
        "connection closed",
        "connection reset",
        "econnreset",
        "broken pipe",
        "software caused connection abort",
    )

    private val HTTP_STATUS_REGEX = Regex("""(\[5\d{2}\]|http\s+5\d{2}|\[429\]|http\s+429)""")
}
