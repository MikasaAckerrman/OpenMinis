package com.openminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class QuotaExceeded(val detail: String) : LLMError("Quota exceeded: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited(val retryAfterMs: Long? = null) : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    /**
     * [T-llmerror-unknown-diagnosable] Anything that is none of the
     * errors above.
     *
     * The message of the cause is often useless on its own. A crash
     * inside the platform says things like "length=492; regionStart=0",
     * which describes a bound and not a problem - and that was all the
     * reader got, because the stack went nowhere. So the stack is
     * written to the log here, at the one place where the cause is still
     * intact, and the text the reader sees names the kind of failure
     * instead of repeating the platform's innards.
     */
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${describe(cause)}", cause) {
        init {
            if (cause != null) {
                runCatching {
                    com.openminis.app.logging.AppLogger.error(
                        "LLM",
                        "unclassified failure ${cause.javaClass.name}: " +
                            cause.stackTraceToString(),
                    )
                }
            }
        }

        companion object {
            /**
             * The text the reader sees.
             *
             * The cause's own message is kept - it often says the useful
             * thing - but it is introduced by the kind of failure, so a
             * platform fault reads as a platform fault and not as a
             * sentence about bounds.
             */
            private fun describe(cause: Throwable?): String {
                val detail = cause?.message?.takeIf { it.isNotBlank() } ?: return "unknown cause"
                val name = cause.javaClass.simpleName
                return if (name.isEmpty() || name == "Exception") detail else "$name: $detail"
            }
        }
    }

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is QuotaExceeded || this is ProviderError

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is QuotaExceeded -> "Quota exceeded"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}
