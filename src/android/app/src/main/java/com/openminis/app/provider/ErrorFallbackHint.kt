package com.openminis.app.provider

/**
 * Decides whether a terminal chat error is the kind that a *backup model*
 * would have recovered — and therefore whether the inline error banner should
 * offer a "configure a fallback model" shortcut instead of leaving the user to
 * guess where to go.
 *
 * ## Why this exists
 *
 * Two failure classes are not the model's fault and not the user's text — they
 * are the *route's* fault, and the fix is always "have another provider in the
 * group to fall through to":
 *
 *  1. **Content-filter rejection** — a relay gateway (new-api / one-api forks)
 *     answers HTTP 500 with "sensitive words detected …" BEFORE the model runs.
 *     Deterministic: the same text trips the same blocklist every retry, so the
 *     only recovery is a different provider whose blocklist differs or is
 *     absent. See [ContentFilterDetection]; its `describe()` string ends with
 *     "another provider usually accepts it".
 *
 *  2. **Rate limit with no fallback left** — under concurrent load one key
 *     trips HTTP 429; if the model group has no other member to jump to, the
 *     turn is stranded. A backup model on a different key/host clears it.
 *
 * In both cases the actionable advice is identical: add a fallback model to the
 * active group. Pure + string-based on purpose: it runs on the render path from
 * the persisted `error` text (which survives a reload while a transient boolean
 * flag would not), and it is trivially unit-testable without a device.
 */
object ErrorFallbackHint {

    /**
     * Substrings (matched case-insensitively) that mark an error a fallback
     * model would recover. Kept additive — a false positive only shows an
     * extra, harmless shortcut button; a false negative sends the user back to
     * guessing.
     */
    private val MARKERS = listOf(
        // content-filter (ContentFilterDetection.describe tail + core phrase)
        "another provider usually accepts it",
        "content filter",
        // rate limit (LLMError.RateLimited message + generic phrasings)
        "rate limited",
        "rate limit",
        "too many requests",
    )

    /** True when the error text names a route-level failure a backup model fixes. */
    fun suggestsAddingFallback(error: String?): Boolean {
        val text = error ?: return false
        return MARKERS.any { text.contains(it, ignoreCase = true) }
    }
}
