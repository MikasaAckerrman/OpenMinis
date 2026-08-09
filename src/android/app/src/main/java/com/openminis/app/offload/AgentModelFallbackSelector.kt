package com.openminis.app.offload

import com.openminis.app.data.model.ModelEntry

/** Pure, deterministic selector for an unconfigured agent model fallback. */
object AgentModelFallbackSelector {
    fun select(candidates: List<ModelEntry>): ModelEntry? = candidates
        .filter { it.model.isTextOutput }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<ModelEntry> { it.model.maxOutputTokens ?: Int.MAX_VALUE }
                .thenBy { it.model.displayName.lowercase() }
                .thenBy { it.id },
        )
        .firstOrNull()
}
