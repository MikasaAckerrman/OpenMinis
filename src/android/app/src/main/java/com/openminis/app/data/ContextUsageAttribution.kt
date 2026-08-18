package com.openminis.app.data

/**
 * Associates provider-reported context usage with the capacity it measured.
 *
 * A usage value from model A cannot be divided by model B's context window.
 * Doing that made a model switch instantly display values such as 130% and
 * previously fed false pressure into automatic maintenance.
 */
data class ContextUsageAttribution private constructor(
    val tokens: Int,
    val modelId: String?,
    val effectiveWindow: Int?,
) {
    fun tokensFor(modelId: String?, effectiveWindow: Int?): Int {
        if (tokens <= 0 || this.modelId.isNullOrBlank() || this.effectiveWindow == null) return 0
        return if (this.modelId == modelId && this.effectiveWindow == effectiveWindow) tokens else 0
    }

    companion object {
        val EMPTY = ContextUsageAttribution(0, null, null)

        fun capture(tokens: Int, modelId: String?, effectiveWindow: Int?): ContextUsageAttribution {
            if (tokens <= 0 || modelId.isNullOrBlank() || effectiveWindow == null || effectiveWindow <= 0) {
                return EMPTY
            }
            return ContextUsageAttribution(tokens, modelId, effectiveWindow)
        }
    }
}
