package com.openminis.app.provider

/** Classifies provider streams that ended without a usable assistant result. */
object EmptyStreamPolicy {
    const val ERROR_DETAIL = "Server returned an empty response (no usable assistant output)"

    private val cleanStopReasons = setOf(
        "stop",
        "end_turn",
        "completed",
        "stop_sequence",
    )

    fun isEmptyResponse(error: Throwable): Boolean =
        error is com.openminis.app.data.model.LLMError.TransientError &&
            error.detail == ERROR_DETAIL

    fun shouldRetry(
        hasVisibleText: Boolean,
        hasCompletedToolCall: Boolean,
        hasMedia: Boolean,
        finishReason: String?,
    ): Boolean {
        if (hasVisibleText || hasCompletedToolCall || hasMedia) return false
        val normalized = finishReason?.trim()?.lowercase()
        return normalized == null || normalized in cleanStopReasons
    }
}
