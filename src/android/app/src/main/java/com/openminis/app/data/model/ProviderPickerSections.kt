package com.openminis.app.data.model

/**
 * [T-provider-ux] Section layout for the chat model picker.
 *
 * Grouping is NOT defined here — it delegates to [ProviderListSections], which
 * is what the settings provider list uses. That is the point: the picker used to
 * render one flat card per instance, so a provider that belongs to no folder had
 * no visible home at all, while the settings screen filed it under its provider
 * type ("OpenAI"). Two screens describing the same providers differently is the
 * "everything mixed together" complaint. Now both answer "where does this
 * provider live" with one function.
 *
 * What this object adds on top: entry counts (an instance whose models were all
 * filtered out must not leave an empty card behind) and the collapse rules.
 */
object ProviderPickerSections {

    data class Section(
        /** Stable key for LazyColumn identity and persisted collapse state. */
        val key: String,
        val title: String,
        val kind: ProviderListSections.Kind,
        /** Member instance ids, in the order the picker should render them. */
        val instanceIds: List<String>,
        /** Total entries across [instanceIds] — shown on the collapsed header. */
        val entryCount: Int,
        /** Provider type for a TYPE section; null for folders. Drives the accent. */
        val type: ProviderType? = null,
    )

    /**
     * Namespace the settings-list key so the two screens keep INDEPENDENT
     * collapse state. Sharing it would mean opening a folder in settings
     * silently opened it in the chat picker, which is not what either gesture
     * asked for.
     */
    fun sectionKey(listKey: String): String = "picker:$listKey"

    /** Convenience for tests and callers that only have a folder name. */
    fun folderKey(name: String): String = sectionKey(ProviderListSections.folderKey(name))

    /**
     * Build the sections.
     *
     * @param instances all enabled instances, in config order.
     * @param instanceEntryCounts instance id → number of entries that survived
     *   the picker's own search filter. Absent or 0 means the instance is
     *   dropped, and a section left with no members disappears rather than
     *   rendering as a bare header.
     */
    fun build(
        instances: List<ProviderInstance>,
        instanceEntryCounts: Map<String, Int>,
    ): List<Section> {
        val visible = instances.filter { (instanceEntryCounts[it.id] ?: 0) > 0 }
        return ProviderListSections.build(visible).map { section ->
            Section(
                key = sectionKey(section.key),
                title = section.title,
                kind = section.kind,
                instanceIds = section.instances.map { it.id },
                entryCount = section.instances.sumOf { instanceEntryCounts[it.id] ?: 0 },
                type = section.type,
            )
        }
    }

    /**
     * Whether a section renders open.
     *
     * Two reasons a section opens:
     *  1. A non-empty search — a match hidden inside a closed section looks like
     *     "no results".
     *  2. It is in [userExpandedKeys].
     *
     * The active model's section is NOT special-cased here. It is seeded into the
     * caller's expanded set instead (see [keysContaining]), because forcing it
     * open on every evaluation would make its chevron a no-op — the user could
     * not close the one section they are most likely to want closed after
     * picking a model.
     */
    fun isExpanded(
        section: Section,
        query: String,
        userExpandedKeys: Set<String>,
    ): Boolean = query.isNotEmpty() || section.key in userExpandedKeys

    /**
     * Keys of the sections holding [instanceId] — the initial expanded set when
     * the picker opens. Returns a set rather than a single key because an
     * instance is in exactly one section today, but seeding several (active entry
     * plus, later, pinned ones) then becomes a non-change.
     */
    fun keysContaining(sections: List<Section>, instanceId: String?): Set<String> {
        if (instanceId == null) return emptySet()
        return sections.filter { instanceId in it.instanceIds }.map { it.key }.toSet()
    }
}
