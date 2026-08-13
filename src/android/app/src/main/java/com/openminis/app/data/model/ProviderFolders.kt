package com.openminis.app.data.model

/**
 * [T-provider-folders] Pure grouping rules for the provider list's
 * user-defined folders. Kept out of the Compose screen so the behavior is
 * unit-testable and so the same rules apply to every future surface that wants
 * folder-aware ordering (model picker, export bundles, minis-config).
 *
 * Folder semantics:
 *  - A folder is nothing but a name written on an instance. There is no folder
 *    entity to create or delete: naming the last instance out of a folder makes
 *    the folder disappear, which is exactly what the user means.
 *  - Names are trimmed and compared case-insensitively so "GoRouter",
 *    "gorouter " and "GOROUTER" cannot split one logical folder into three.
 *    The DISPLAY name is the first spelling encountered in config order, so the
 *    user's own capitalization wins over an alphabetically earlier variant.
 */
object ProviderFolders {

    /** Trimmed folder name, or null when the instance is ungrouped. */
    fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** Case/whitespace-insensitive identity used to merge spelling variants. */
    fun key(raw: String?): String? = normalize(raw)?.lowercase()

    data class Section(
        /** Display name — the first spelling seen in config order. */
        val name: String,
        val instances: List<ProviderInstance>,
    )

    data class Layout(
        /** User-defined folders, alphabetical by display name (case-insensitive). */
        val folders: List<Section>,
        /** Instances with no folder, in their original config order. */
        val ungrouped: List<ProviderInstance>,
    )

    fun layout(instances: List<ProviderInstance>): Layout {
        // LinkedHashMap keyed by the case-folded name preserves first-seen
        // spelling AND first-seen order; we sort at the end for display.
        val buckets = LinkedHashMap<String, MutableList<ProviderInstance>>()
        val displayNames = LinkedHashMap<String, String>()
        val ungrouped = ArrayList<ProviderInstance>()

        for (inst in instances) {
            val name = normalize(inst.folder)
            if (name == null) {
                ungrouped.add(inst)
                continue
            }
            val k = name.lowercase()
            displayNames.getOrPut(k) { name }
            buckets.getOrPut(k) { ArrayList() }.add(inst)
        }

        val folders = buckets.entries
            .map { (k, list) -> Section(displayNames[k] ?: k, list) }
            .sortedBy { it.name.lowercase() }

        return Layout(folders = folders, ungrouped = ungrouped)
    }

    /**
     * Existing folder display names across [instances], deduplicated
     * case-insensitively and sorted — the suggestion chips in the provider
     * detail screen. Offering these is what keeps a 17-key folder from
     * becoming two 8-and-9-key folders after one typo.
     */
    fun existingNames(instances: List<ProviderInstance>): List<String> =
        layout(instances).folders.map { it.name }
}
