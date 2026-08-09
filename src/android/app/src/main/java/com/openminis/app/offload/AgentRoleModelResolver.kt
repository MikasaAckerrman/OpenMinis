package com.openminis.app.offload

import com.openminis.app.data.model.ModelEntry

/**
 * [T-agent-role-models] Pure model of ProviderRepository.resolveModelEntryForRole's
 * precedence, extracted so the ordering can be proven without Android/prefs.
 *
 * The bug this guards against: an unset per-role choice used to fall through to a
 * catalog pick, which selected a model on a provider the user never funded. The
 * user-facing contract is "agents use the model I chat with unless I say otherwise",
 * so `currentModel` MUST beat the catalog fallback.
 */
object AgentRoleModelResolver {
    enum class Source { ROLE_KEY, PER_ROLE_SETTING, SHARED_DEFAULT, CURRENT_MODEL, CATALOG_FALLBACK, NONE }

    data class Resolution(val entry: ModelEntry?, val source: Source)

    fun resolve(
        roleKeyEntry: ModelEntry? = null,
        perRoleEntry: ModelEntry? = null,
        sharedDefaultEntry: ModelEntry? = null,
        currentModelEntry: ModelEntry? = null,
        catalogCandidates: List<ModelEntry> = emptyList(),
    ): Resolution {
        roleKeyEntry?.let { return Resolution(it, Source.ROLE_KEY) }
        perRoleEntry?.let { return Resolution(it, Source.PER_ROLE_SETTING) }
        sharedDefaultEntry?.let { return Resolution(it, Source.SHARED_DEFAULT) }
        currentModelEntry?.let { return Resolution(it, Source.CURRENT_MODEL) }
        val fallback = AgentModelFallbackSelector.select(catalogCandidates)
            ?: return Resolution(null, Source.NONE)
        return Resolution(fallback, Source.CATALOG_FALLBACK)
    }
}
