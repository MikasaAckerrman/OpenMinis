package com.openminis.app.data

/**
 * [T-compact-route] How a compaction attempt should react to a provider
 * refusal, so `/compact` can no longer strand a session.
 *
 * ## The failure this fixes
 *
 * `generateCompactSummary` called `currentProvider` and nothing else. When the
 * bound model answered 429 (rate limited) or 403-quota, the whole compaction
 * died with "Compaction failed: Quota exceeded: 预扣费额度失败…" and the session
 * stayed as oversized as before — the user is then stuck: too big to send, and
 * the tool that shrinks it refuses to run. Meanwhile the model group may hold
 * three other working models, and the device can always compress locally.
 *
 * ## Why max_tokens matters here (non-obvious)
 *
 * new-api/one-api relays PRE-CHARGE the worst-case cost of a request before
 * running it: `need quota: $1.04` against `remain quota: $0.21` in the
 * observed body. That worst case is driven by `max_tokens`, which compaction
 * sets as high as 8192. So a quota rejection is not necessarily "you are out
 * of money" — it can be "you cannot afford THIS request as sized". Asking for
 * a shorter summary lowers the pre-charge and often goes through on the same
 * key, which is why the ladder below retries smaller before moving on.
 *
 * A rate limit (429) is the opposite: size is irrelevant, so retrying smaller
 * on the same provider is pure waste and the ladder skips straight to the next
 * model.
 *
 * Pure logic, no Android — the escalation matrix is unit-tested.
 */
object CompactRoute {

    /** What to do after an attempt failed. */
    sealed class Step {
        /** Retry the SAME provider with a smaller output budget. */
        data class RetrySmaller(val maxTokens: Int) : Step()

        /** Move to the next provider in the group (index into the fallback list). */
        data class NextProvider(val index: Int) : Step()

        /** No LLM route left — build the summary on-device instead. */
        object LocalDigest : Step()

        /** Not a routing failure (e.g. bad request): surface it. */
        data class Surface(val reason: String) : Step()
    }

    /** Smallest output budget worth asking for — below this a summary is useless. */
    const val MIN_MAX_TOKENS = 1_024

    /** Quota rejections are retried at most this many times per provider. */
    const val MAX_SHRINK_STEPS = 2

    private val RATE_LIMIT_MARKERS = listOf(
        "rate limit", "rate_limit", "429", "too many requests",
        "请求过于频繁", "请求频率",
    )

    fun isRateLimit(message: String): Boolean {
        val m = message.lowercase()
        return RATE_LIMIT_MARKERS.any { m.contains(it) }
    }

    /**
     * Quota/balance rejection. Reuses the marker set the provider layer already
     * maintains for HTTP bodies ([com.openminis.app.provider.QuotaErrorDetection])
     * by matching on the mapped error text, which embeds the gateway message.
     */
    fun isQuota(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("quota") ||
            m.contains("insufficient balance") ||
            m.contains("余额不足") ||
            m.contains("额度不足") ||
            m.contains("预扣费")
    }

    /**
     * Halve the budget, floored. Returns null when there is no smaller budget
     * left worth trying — the caller then moves on rather than looping on 1024.
     */
    fun shrink(currentMaxTokens: Int): Int? {
        val next = currentMaxTokens / 2
        return if (next >= MIN_MAX_TOKENS) next else null
    }

    /**
     * @param errorMessage the failure just seen
     * @param currentMaxTokens output budget the failed attempt asked for
     * @param shrinkStepsUsed how many RetrySmaller steps already happened on
     *        THIS provider
     * @param nextProviderIndex index of the next untried fallback provider, or
     *        null when the list is exhausted
     */
    fun next(
        errorMessage: String,
        currentMaxTokens: Int,
        shrinkStepsUsed: Int,
        nextProviderIndex: Int?,
    ): Step {
        val quota = isQuota(errorMessage)
        val rateLimited = isRateLimit(errorMessage)
        val contentFiltered = com.openminis.app.provider.ContentFilterDetection
            .isContentFilterRejection(errorMessage)
        if (!quota && !rateLimited && !contentFiltered) return Step.Surface(errorMessage)
        if (contentFiltered) {
            return nextProviderIndex?.let { Step.NextProvider(it) } ?: Step.LocalDigest
        }

        // Quota only: a smaller request may be affordable. Rate limit: it
        // won't be — the limit is per-request-count, not per-token.
        if (quota && !rateLimited && shrinkStepsUsed < MAX_SHRINK_STEPS) {
            shrink(currentMaxTokens)?.let { return Step.RetrySmaller(it) }
        }
        nextProviderIndex?.let { return Step.NextProvider(it) }
        return Step.LocalDigest
    }
}
