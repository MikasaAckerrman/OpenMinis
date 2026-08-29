package com.openminis.app.data.model

/**
 * [T-provider-ux] Grouping for the chat model picker: instances that share a
 * folder are gathered under one collapsible folder block, everything else stays
 * a top-level block exactly as before.
 *
 * Why: the picker listed one card per provider instance. With a dozen keys on
 * the same gateway that is a dozen near-identical cards to scroll past, which is
 * the "everything mixed together" complaint. Folders already exist as a concept
 * in the provider list, so the picker reuses [ProviderFolders] rather than
 * inventing a second grouping rule that could disagree with the settings screen.
 *
 * Ordering is deliberately NOT alphabetical across the whole list: folders keep
 * [ProviderFolders]'s alphabetical order and appear first, then loose instances
 * in config order. That matches the provider list, so the two screens read the
 * same way.
 */
object ProviderPickerSections {

    sealed interface Block {
        /** Stable key for LazyColumn identity and persisted collapse state. */
        val key: String

        /** A user folder holding one or more instances. */
        data class Folder(
            override val key: String,
            val name: String,
            /** Instance id → its entries, in the order the picker should render. */
            val instances: List<String>,
            /** Total entries across [instances] — shown on the collapsed header. */
            val entryCount: Int,
        ) : Block

        /** A single instance with no folder. */
        data class Loose(
            override val key: String,
            val instanceId: String,
        ) : Block
    }

    /**
     * Stable key for a picker folder block.
     *
     * Whitespace-collapsed for the same reason as
     * [ProviderListSections.folderKey]: the collapsed-state store is a
     * newline-delimited string, and a folder name can reach the app through the
     * provider-import path where nothing strips newlines.
     */
    fun folderKey(name: String): String =
        "pickerFolder:" + name.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Build the block list.
     *
     * @param instanceEntryCounts instance id → number of entries that survived
     *   the picker's own search filter. Instances absent from this map (or with
     *   0) are dropped: the picker already removes empty providers, and a folder
     *   whose every child was filtered out must disappear rather than render as
     *   an empty header.
     * @param instances all enabled instances, in config order.
     */
    fun build(
        instances: List<ProviderInstance>,
        instanceEntryCounts: Map<String, Int>,
    ): List<Block> {
        val visible = instances.filter { (instanceEntryCounts[it.id] ?: 0) > 0 }
        val layout = ProviderFolders.layout(visible)
        val out = ArrayList<Block>()

        for (folder in layout.folders) {
            // A folder with a single instance is NOT worth a nesting level — it
            // would add a tap to reach one provider and make the list deeper for
            // no grouping benefit. Render it as if it were loose.
            if (folder.instances.size == 1) {
                val only = folder.instances.first()
                out.add(Block.Loose(key = "pickerInstance:${only.id}", instanceId = only.id))
                continue
            }
            out.add(
                Block.Folder(
                    key = folderKey(folder.name),
                    name = folder.name,
                    instances = folder.instances.map { it.id },
                    entryCount = folder.instances.sumOf { instanceEntryCounts[it.id] ?: 0 },
                ),
            )
        }

        for (inst in layout.ungrouped) {
            out.add(Block.Loose(key = "pickerInstance:${inst.id}", instanceId = inst.id))
        }

        return out
    }

    /**
     * Whether a folder block renders open.
     *
     * Same rule as the provider list: collapsed by default, but a non-empty
     * search forces every folder open, because a match hidden inside a closed
     * folder looks like "no results".
     */
    fun isFolderExpanded(
        block: Block.Folder,
        query: String,
        expandedKeys: Set<String>,
    ): Boolean = query.isNotEmpty() || block.key in expandedKeys
}
