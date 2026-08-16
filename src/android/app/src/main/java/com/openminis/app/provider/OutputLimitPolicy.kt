package com.openminis.app.provider

/** Normalizes provider-specific output-limit finish reasons. */
object OutputLimitPolicy {
    private val limitReasons = setOf(
        "length",
        "max_tokens",
        "max_output_tokens",
    )

    fun reachedLimit(finishReason: String?): Boolean =
        finishReason
            ?.trim()
            ?.lowercase()
            ?.let(limitReasons::contains) == true
}
