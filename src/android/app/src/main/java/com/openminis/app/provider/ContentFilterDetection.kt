package com.openminis.app.provider

/**
 * Recognise a gateway's CONTENT MODERATION rejection, which relays confusingly
 * report with a 5xx status.
 *
 * ## Why this exists
 *
 * OpenAI-compatible relays (new-api / one-api forks: AgentRouter, gorouter,
 * tabitoken, …) run a keyword filter over the request before forwarding it
 * upstream. When a word trips the filter they answer, observed live on
 * 2026-08-10 against AgentRouter with model gpt-5.6-sol:
 *
 * ```
 * HTTP 500 {"error":{"message":"sensitive words detected
 *   (request id: 20260810184932592602059qxpxI8x8MWVm)"}}
 * ```
 *
 * [com.openminis.app.provider.openai.OpenAIProvider.mapHttpError] treats every
 * 500 as [com.openminis.app.data.model.LLMError.TransientError], which is
 * `isRetryable` — so the app resent the IDENTICAL body twice more and got the
 * identical rejection, showing the user "Transient error … retrying (2/3)" for
 * something that could never succeed.
 *
 * A moderation verdict is DETERMINISTIC: the same text always trips the same
 * filter. It is therefore the opposite of transient, and the right response is
 * the group fallback — another provider does not necessarily run the same
 * blocklist, and Anthropic/OpenAI direct endpoints have no such filter at all.
 * Classifying it as `ProviderError` (`isFallbackable`) makes that happen and
 * stops three doomed round-trips.
 *
 * Same shape of bug, and same fix, as [QuotaErrorDetection]: the status code
 * lies, only the body tells the truth. Keep the list additive — a false
 * positive costs one skipped retry on a request that failed anyway, a false
 * negative costs the user three pointless retries and a wrong explanation.
 */
object ContentFilterDetection {

    /**
     * Substrings identifying a moderation/keyword rejection. Matched
     * case-insensitively against the raw response body.
     */
    private val FILTER_MARKERS = listOf(
        // -- English, as emitted by new-api forks --
        "sensitive words detected",
        "sensitive word",
        "sensitive_words",
        "content_filter",
        "content filtering",
        "prompt blocked",
        "blocked by content",
        "risk control",
        // -- Chinese: these relays are Chinese-authored and often reply in it --
        "敏感词",
        "内容审核",
        "违规内容",
        "命中敏感",
    )

    /** True when [body] is a moderation rejection rather than a server fault. */
    fun isContentFilterRejection(body: String): Boolean =
        FILTER_MARKERS.any { body.contains(it, ignoreCase = true) }

    /**
     * Human-readable one-liner.
     *
     * Explains WHOSE filter fired, because the natural reading of "sensitive
     * words detected" is that the model refused — it did not, the request never
     * reached it. Names the next step too: the same text on another provider
     * usually goes through.
     */
    fun describe(body: String, limit: Int = 300): String {
        val detail = extractMessage(body)?.trim().orEmpty()
        val head = if (detail.isNotEmpty()) detail else body.take(limit).trim()
        return "the gateway's content filter rejected this request before it " +
            "reached the model ($head) — the text itself is fine for the model; " +
            "another provider usually accepts it"
    }

    /**
     * Pull `error.message` out without a JSON parse: this runs on an error path
     * where the body may be truncated, HTML, or not JSON at all, and a parser
     * exception here would mask the real failure.
     */
    private fun extractMessage(body: String): String? {
        val key = "\"message\""
        val keyAt = body.indexOf(key, ignoreCase = true, startIndex = 0)
        if (keyAt < 0) return null
        val colon = body.indexOf(':', keyAt + key.length)
        if (colon < 0) return null
        val open = body.indexOf('"', colon + 1)
        if (open < 0) return null
        val sb = StringBuilder()
        var i = open + 1
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                sb.append(body[i + 1]); i += 2; continue
            }
            if (c == '"') break
            sb.append(c)
            i++
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
