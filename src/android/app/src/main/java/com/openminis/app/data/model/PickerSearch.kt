package com.openminis.app.data.model

/**
 * [T-provider-ux] Search rules for the chat model picker.
 *
 * The picker's search only ever matched MODEL names, which is the wrong default
 * for a collection where a dozen providers serve overlapping model lists: to
 * reach "the sonnet key on gorouter" you had to remember a model id, and typing
 * the provider you were actually thinking of returned nothing.
 *
 * Rule, mirroring [ProviderListSections] so both screens behave the same:
 *  - The query is matched against the PROVIDER first (label, type, folder,
 *    endpoint). A provider match shows ALL of its models — someone searching
 *    "gorouter" means the provider, not one model inside it.
 *  - Otherwise the query is matched against the models, and only the matching
 *    ones are shown.
 *
 * Generic over the entry type so this stays free of Compose and of model
 * fixtures: the caller says which strings of an entry are searchable.
 */
object PickerSearch {

    /**
     * Fields of a provider a query is matched against.
     *
     * Delegates to [ProviderListSections.instanceMatches] rather than repeating
     * the field list: two copies would drift into a query that finds a provider
     * on the settings screen but not in the picker.
     */
    fun matchesProvider(instance: ProviderInstance, query: String): Boolean =
        ProviderListSections.instanceMatches(instance, query)

    /**
     * Entries of [instance] that survive [query].
     *
     * @param entryTexts searchable strings of one entry (display name, id, …).
     */
    fun <T> visibleEntries(
        instance: ProviderInstance,
        entries: List<T>,
        query: String,
        entryTexts: (T) -> List<String?>,
    ): List<T> {
        if (query.isEmpty()) return entries
        // Provider match wins over per-entry matching: "gorouter" should not
        // silently hide the models on the provider it just found.
        if (matchesProvider(instance, query)) return entries
        return entries.filter { e -> FuzzySearch.matchesAny(query, *entryTexts(e).toTypedArray()) }
    }
}
