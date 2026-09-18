package com.openminis.app.data.model

/**
 * [T-provider-ux] Section layout for the provider list: user folders first, then
 * the remaining instances grouped by provider type, with search applied.
 *
 * Why a separate object rather than inline Compose logic: the list has three
 * behaviours that interact and each one is easy to get subtly wrong —
 * collapse-by-default, search that must reach INTO collapsed sections, and
 * two visually different section kinds that must otherwise behave identically.
 * Deciding all of it in one pure function means the screen just renders what it
 * is handed, and the interactions are unit-tested instead of eyeballed.
 *
 * Folder grouping itself is delegated to [ProviderFolders] so there is exactly
 * one definition of "what folder is this in" in the codebase.
 */
object ProviderListSections {

    enum class Kind {
        /** A user-named folder (e.g. "GoRouter"). */
        FOLDER,

        /** An automatic group of ungrouped instances of one provider type. */
        TYPE,
    }

    data class Section(
        /**
         * Stable identity used to persist the collapsed state. Prefixed by kind
         * so a folder literally named "openai" cannot collide with the OpenAI
         * type section.
         */
        val key: String,
        /** Header text. */
        val title: String,
        val kind: Kind,
        val instances: List<ProviderInstance>,
        /**
         * True when the section only survived the filter because its own TITLE
         * matched — in that case every instance inside is shown, since the user
         * searching "gorouter" means the folder, not one key in it.
         */
        val matchedByTitle: Boolean = false,
        /**
         * Provider type for a [Kind.TYPE] section, null for folders. Carried
         * here so the UI can tint the header with the same brand colour as the
         * dots on the rows inside it, without re-deriving the type from the
         * title string.
         */
        val type: ProviderType? = null,
    )

    /**
     * Stable key for a folder section.
     *
     * Derived from [ProviderFolders.key], which is the single definition of
     * folder identity — spelling variants that get merged into one folder must
     * address one section, and the collapsed-state store is newline-delimited so
     * a name carrying a newline (reachable via provider import, where nothing
     * strips control characters) must not inject a delimiter.
     */
    fun folderKey(name: String): String =
        "folder:" + (ProviderFolders.key(name) ?: "")

    fun typeKey(type: ProviderType): String = "type:" + type.name

    /**
     * Fields a search query is matched against for one instance.
     *
     * Includes the endpoint because with a dozen same-named relay keys the URL
     * is often the only thing that tells them apart, and excludes the API key
     * for the obvious reason.
     *
     * Public because the chat model picker matches providers by the same rule
     * (via [PickerSearch]); two definitions would mean a query that finds a
     * provider in settings and not in the picker.
     */
    fun instanceMatches(inst: ProviderInstance, query: String): Boolean =
        FuzzySearch.matchesAny(
            query,
            inst.label,
            inst.providerType.displayName,
            inst.folder,
            inst.customBaseURL,
        )

    /**
     * Build the sections for [instances], filtered by [query].
     *
     * An empty query returns everything. A non-empty query keeps a section when
     * its title matches (all children shown) or when at least one child matches
     * (only the matching children shown). Empty sections are dropped rather than
     * left as bare headers.
     */
    fun build(instances: List<ProviderInstance>, query: String = ""): List<Section> {
        val layout = ProviderFolders.layout(instances)
        val out = ArrayList<Section>()

        for (folder in layout.folders) {
            val titleMatch = FuzzySearch.matches(folder.name, query)
            val kept = if (titleMatch) folder.instances
            else folder.instances.filter { instanceMatches(it, query) }
            if (kept.isEmpty()) continue
            out.add(
                Section(
                    key = folderKey(folder.name),
                    title = folder.name,
                    kind = Kind.FOLDER,
                    instances = kept,
                    matchedByTitle = titleMatch && query.isNotEmpty(),
                ),
            )
        }

        // groupBy preserves first-encounter order of keys, which keeps the type
        // sections in config order exactly as the screen rendered them before.
        for ((type, list) in layout.ungrouped.groupBy { it.providerType }) {
            val titleMatch = FuzzySearch.matches(type.displayName, query)
            val kept = if (titleMatch) list else list.filter { instanceMatches(it, query) }
            if (kept.isEmpty()) continue
            out.add(
                Section(
                    key = typeKey(type),
                    title = type.displayName,
                    kind = Kind.TYPE,
                    instances = kept,
                    matchedByTitle = titleMatch && query.isNotEmpty(),
                    type = type,
                ),
            )
        }

        return out
    }

    /**
     * Whether a section should render expanded.
     *
     * The rule that matters: while searching, sections are FORCED open. Collapsed
     * sections plus a search field is a trap — the user types a name, the row is
     * three collapsed folders deep, and the screen looks empty. Outside search,
     * the user's own choice wins, defaulting to collapsed as requested.
     */
    fun isExpanded(
        section: Section,
        query: String,
        userExpandedKeys: Set<String>,
    ): Boolean = if (query.isNotEmpty()) true else section.key in userExpandedKeys

    /** Total instances across [sections] — the "N of M shown" search summary. */
    fun instanceCount(sections: List<Section>): Int = sections.sumOf { it.instances.size }
}
