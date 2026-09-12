package com.openminis.app.data

/**
 * [T-exhausted-transient-falls-back] Should a failed turn hand over to the next
 * provider in the model group, or fail on this one?
 *
 * ## The bug this fixes
 *
 * The user reported two errors repeating "constantly", each in a different
 * session:
 *
 *   Transient error: HTTP 502: error code: 502
 *   Transient error: no response from server (120s) — check network/proxy
 *
 * Both are [com.openminis.app.data.model.LLMError.TransientError]: mapHttpError
 * routes 500/502/503/504/529 there, and the TTFB watchdog throws it directly.
 *
 * The old decision was `isRateLimit || isContentFilter || is5xx || isEmpty ||
 * strategy == always`, and `is5xx` is defined as *ProviderError whose detail
 * matches `[5xx]` or `HTTP 5xx`*. The type check makes it false for every
 * HTTP-status 5xx that arrived through mapHttpError — that is, for the shape it
 * was written to catch. So under the `default` strategy a 502 burst exhausted
 * its retries on one gateway and then failed the turn, never trying the other
 * members of the group. One bad upstream, no escape hatch, which is exactly what
 * "always this error" looks like from the outside.
 *
 * ## Why "exhausted" and not "transient"
 *
 * Falling back on the FIRST transient failure would abandon the provider the
 * user picked over a blip that usually clears on retry — and with
 * [com.openminis.app.data.TransientRetryBudget] the retry is cheap and
 * class-aware. So the retry budget runs first; handover happens only once this
 * provider has actually spent it.
 *
 * Kept pure so the decision is testable without a ViewModel, a network stack or
 * a provider group — and so the reasoning above lives next to the rule instead
 * of inside a 12000-line file.
 */
object FallbackDecision {

    /**
     * @param isRateLimit provider-level 429. Handed over immediately: waiting out
     *   someone else's quota window is worse than trying another key.
     * @param isContentFilter deterministic moderation rejection. Retrying the
     *   same provider cannot succeed; a provider with a different blocklist can.
     * @param isHttp5xxProviderError the legacy `is5xx` signal — a ProviderError
     *   carrying an HTTP 5xx shape. Kept as its own input rather than folded into
     *   [transientRetriesExhausted] because it fires WITHOUT spending retries.
     * @param isEmptyResponse provider closed the stream having sent nothing.
     * @param transientRetriesExhausted this turn is transient AND has used its
     *   whole per-class retry budget on the current provider.
     * @param strategyIsAlways the group's fallbackStrategy is `always`.
     */
    fun shouldFallback(
        isRateLimit: Boolean,
        isContentFilter: Boolean,
        isHttp5xxProviderError: Boolean,
        isEmptyResponse: Boolean,
        transientRetriesExhausted: Boolean,
        strategyIsAlways: Boolean,
    ): Boolean = isRateLimit ||
        isContentFilter ||
        isHttp5xxProviderError ||
        isEmptyResponse ||
        transientRetriesExhausted ||
        strategyIsAlways

    /**
     * Has this turn spent its transient retry budget on the current provider?
     *
     * `attemptsUsed >= maxAttempts` rather than `==`: the budget is read per
     * failure from [TransientRetryBudget.maxAttempts], and a classification that
     * changes between attempts (a 502 burst that ends as a dropped socket) can
     * lower the ceiling below the count already spent. `==` would miss that and
     * silently strand the turn — the failure mode this whole policy exists to
     * remove.
     */
    fun transientBudgetSpent(
        isTransient: Boolean,
        attemptsUsed: Int,
        maxAttempts: Int,
    ): Boolean = isTransient && attemptsUsed >= maxAttempts
}
