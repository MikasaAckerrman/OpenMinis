package com.openminis.app.provider

/**
 * Recognise "you are out of credit" gateway responses, whatever wording the
 * relay happens to use this week.
 *
 * ## Why this exists
 *
 * OpenAI-compatible relays (new-api / one-api forks: AgentRouter, gorouter,
 * tabitoken, …) reject a request BEFORE running it when the account balance
 * can't cover the worst-case cost of `max_tokens`. They answer HTTP 403 —
 * the same status a revoked credential produces — and the only thing that
 * tells the two apart is the body.
 *
 * The provider error mappers used to test for exactly two literals
 * (`insufficient_user_quota` and the Chinese `预扣费额度失败`). Any other
 * spelling fell through to [com.openminis.app.data.model.LLMError.InvalidApiKey],
 * so a live, freshly-rotated key was reported to the user as
 * "Invalid API key" — sending them to re-check credentials that were never
 * the problem. Observed live on 2026-08-10 with a valid AgentRouter key:
 *
 * ```
 * HTTP 403 {"error":{"code":"pre_consume_token_quota_failed",
 *   "message":"token quota is not enough, token remain quota: ＄0.219130,
 *              need quota: ＄1.040466"}}
 * → ChatVMStream EXCEPTION InvalidApiKey: Invalid API key
 * ```
 *
 * The same relay also emits `insufficient_user_quota` with the English
 * "pre-consume quota failed …" text, which is why the marker list covers
 * error CODES, English PHRASES and Chinese PHRASES independently: relays mix
 * and match them across endpoints and versions, and matching only one axis
 * is what let this bug through.
 *
 * Keep this list additive. A false positive costs a slightly wrong label on a
 * request that failed anyway; a false negative costs the user an hour of
 * debugging a credential that works.
 */
object QuotaErrorDetection {

    /**
     * Substrings that identify a balance/quota rejection. Matched
     * case-insensitively against the raw response body.
     */
    private val QUOTA_MARKERS = listOf(
        // -- new-api / one-api error codes --
        "insufficient_user_quota",
        "pre_consume_token_quota_failed",
        "insufficient_quota",
        "quota_exceeded",
        "billing_hard_limit_reached",
        // -- English phrasings --
        "pre-consume quota failed",
        "token quota is not enough",
        "quota is not enough",
        "insufficient balance",
        "exceeded your current quota",
        // -- Chinese phrasings --
        "预扣费额度失败",
        "额度不足",
        "余额不足",
    )

    /** True when [body] is a quota/balance rejection rather than an auth failure. */
    fun isQuotaFailure(body: String): Boolean =
        QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }

    /**
     * Human-readable one-liner for the error surface.
     *
     * Prefers the gateway's own `error.message` (it carries the useful part —
     * remaining balance and required amount) over dumping raw JSON at the
     * user. Falls back to a truncated body when the shape is unfamiliar, so
     * an unknown relay never degrades to an empty message.
     *
     * Hand-rolled extraction instead of a JSON parse: this runs on an error
     * path where the body may be truncated, HTML, or not JSON at all, and a
     * parser exception here would mask the real failure.
     */
    fun describe(body: String, limit: Int = 500): String {
        val message = extractErrorMessage(body)
        if (!message.isNullOrBlank()) return message.take(limit)
        return body.take(limit)
    }

    private fun extractErrorMessage(body: String): String? {
        val key = "\"message\""
        val keyAt = body.indexOf(key)
        if (keyAt < 0) return null
        val colon = body.indexOf(':', keyAt + key.length)
        if (colon < 0) return null
        val open = body.indexOf('"', colon + 1)
        if (open < 0) return null
        val sb = StringBuilder()
        var i = open + 1
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    // Keep escaped content readable: \" → ", \n → space.
                    when (val next = body[i + 1]) {
                        '"', '\\', '/' -> sb.append(next)
                        'n', 'r', 't' -> sb.append(' ')
                        else -> { sb.append('\\'); sb.append(next) }
                    }
                    i += 2
                }
                c == '"' -> return sb.toString().trim()
                else -> { sb.append(c); i++ }
            }
        }
        // Unterminated string (truncated body) — return what we recovered.
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }
}
