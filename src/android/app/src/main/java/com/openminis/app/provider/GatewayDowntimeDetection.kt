package com.openminis.app.provider

/**
 * Recognise "the relay itself is down / saturated" responses that gateways
 * wrongly carry as HTTP 401 / 403.
 *
 * ## Why this exists
 *
 * [QuotaErrorDetection] already handles the 403 == "balance too low" trap.
 * The mirror-image bug is a gateway that answers 401 when ITS upstream session
 * dies, even though the caller's credential is perfectly valid. The provider
 * mappers then produce [com.openminis.app.data.model.LLMError.InvalidApiKey],
 * which is doubly wrong: the user is sent to re-check a key that works, AND the
 * error is non-retryable so the request is abandoned instead of retried.
 *
 * Observed live 2026-09-15 with a valid relay key:
 *
 * ```
 * HTTP 401 {"type":"error","error":{"type":"authentication_error",
 *   "message":"Нейросеть слишком глубоко задумалась о смысле бытия и временно
 *              вышла в астрал. Дайте кремниевому мозгу 30 секунд на перекур…"}}
 * → ChatVMStream EXCEPTION InvalidApiKey: Invalid API key
 * ```
 *
 * A genuinely-broken key on the SAME gateway returns the literal "Invalid API
 * key" instead, so the two are separated by content, not status code — same
 * trick [QuotaErrorDetection] uses for 403.
 *
 * ## Design notes
 *
 * - [isDowngradeSafe] is an AND-NOT: a body that says the key is bad wins even
 *   if it also happens to contain a downtime word, because "your key is wrong"
 *   is actionable and must not be softened into "try later".
 * - Markers are deliberately specific. A false positive here turns a
 *   non-retryable InvalidApiKey into a retryable TransientError; the request is
 *   already failed, so the only cost is one extra retry that also fails.
 *
 * Keep this list additive; see [QuotaErrorDetection] for the same discipline.
 */
object GatewayDowntimeDetection {

    /** Substrings identifying "provider temporarily unavailable", case-insensitive. */
    private val DOWNTIME_MARKERS = listOf(
        // -- Russian relays (wording captured live from a syntro-class gateway) --
        "вышла в астрал",       // "the model wandered off into the astral"
        "на перекур",           // "give it a smoke break"
        "временно вышел",       // "temporarily went away"
        "временно недоступ",    // "temporarily unavailable"
        "недоступен сейчас",     // "unavailable right now"
        "попробуйте позже",      // "try again later"
        "попробуйте через",      // "try again in N seconds"
        // -- English wordings --
        "temporarily unavailable",
        "service unavailable",
        "server is busy",
        "try again later",
        "please retry",
        "upstream error",
        "upstreamconnect",
        "no healthy upstream",
        "overloaded",
        // -- Chinese new-api family --
        "服务器繁忙",
        "服务不可用",
        "请稍后",
        "上游错误",     // "upstream error" — specific; bare 上游 just means "upstream"
    )

    /**
     * Phrases that unambiguously blame the CALLER's credential. When any of
     * these appear the response must stay an InvalidApiKey no matter what other
     * words the body contains. Matched lowercase.
     */
    private val KEY_IS_BAD_MARKERS = listOf(
        "invalid api key",
        "invalid api-key",
        "incorrect api key",
        "api key is invalid",
        "key not found",
        "invalid token",
        "expired token",
        "unauthorized: your api",
        "上游 api key 无效",  // "upstream api key invalid"
        "api key 无效",       // "api key invalid"
    )

    /** True when [body] reports provider downtime AND not an explicit key error. */
    fun isDowntimeFailure(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        if (KEY_IS_BAD_MARKERS.any { lower.contains(it) }) return false
        return DOWNTIME_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    /** Human-readable one-liner: the relay's own message, else truncated body. */
    fun describe(body: String, limit: Int = 500): String =
        QuotaErrorDetection.extractRelayMessage(body)?.takeIf { it.isNotBlank() }?.take(limit)
            ?: body.take(limit)
}
