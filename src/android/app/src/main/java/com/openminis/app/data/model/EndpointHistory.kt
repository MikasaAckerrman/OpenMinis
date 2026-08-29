package com.openminis.app.data.model

/**
 * [T-provider-ux] The endpoints the user has already typed, mined from existing
 * provider instances so the add-provider form can offer them instead of making
 * the user retype a gateway host from memory.
 *
 * Why mining beats a stored history list: the instances ARE the history, and
 * they cannot go stale. A separate persisted list would need writing on save,
 * pruning on delete, and migrating on import — three ways to disagree with
 * reality. Deriving on the fly means a deleted provider's URL disappears from
 * the suggestions exactly when it should.
 */
object EndpointHistory {

    /**
     * Normalized form used to merge spellings of the same host. Trailing
     * slashes and scheme/host case are meaningless to a URL but would otherwise
     * split "https://gorouter.app/" and "https://GoRouter.app" into three
     * separate suggestions for what the user thinks of as one gateway.
     *
     * Only the scheme+authority are lowercased; the path is left alone because
     * a relay path CAN be case-sensitive (e.g. "/v1/Anthropic").
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val sep = trimmed.indexOf("://")
        if (sep < 0) return trimmed
        val schemeEnd = sep + 3
        val pathStart = trimmed.indexOf('/', schemeEnd)
        return if (pathStart < 0) {
            trimmed.lowercase()
        } else {
            trimmed.substring(0, pathStart).lowercase() + trimmed.substring(pathStart)
        }
    }

    /**
     * One suggestion row.
     *
     * [usedByOtherType] is the interesting flag: an endpoint that has only ever
     * served a DIFFERENT provider type is usually the wrong paste (an Anthropic
     * relay host dropped into an OpenAI provider yields a 404 that looks like a
     * key problem). The row is still offered — third-party gateways legitimately
     * serve several surfaces — but the UI can mark it.
     */
    data class Suggestion(
        /** Display/insert value — the first spelling the user typed. */
        val url: String,
        /** How many existing instances use this endpoint. */
        val useCount: Int,
        /** Provider types seen on this endpoint, in first-seen order. */
        val types: List<ProviderType>,
        /** True when [types] contains no entry equal to the requested type. */
        val usedByOtherType: Boolean,
        /** Labels of instances using it — lets the UI explain where it came from. */
        val labels: List<String>,
    )

    /**
     * Suggestions for a provider of [forType], most-used first.
     *
     * Ordering: use count descending, then alphabetical. Frequency first is what
     * makes the list useful — the gateway with 12 keys belongs at the top — and
     * the alphabetical tiebreak keeps the order stable instead of dependent on
     * config insertion order.
     *
     * [forType] null means "no type context yet": nothing is flagged as foreign.
     */
    fun suggestions(
        instances: List<ProviderInstance>,
        forType: ProviderType? = null,
    ): List<Suggestion> {
        // Keyed by normalized URL; keeps first-seen spelling and first-seen
        // type/label order, so the user's own capitalization wins.
        val display = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        val types = LinkedHashMap<String, MutableList<ProviderType>>()
        val labels = LinkedHashMap<String, MutableList<String>>()

        for (inst in instances) {
            val key = normalize(inst.customBaseURL) ?: continue
            val spelling = inst.customBaseURL?.trim()?.trimEnd('/') ?: continue
            display.getOrPut(key) { spelling }
            counts[key] = (counts[key] ?: 0) + 1
            types.getOrPut(key) { ArrayList() }.let { if (inst.providerType !in it) it.add(inst.providerType) }
            val label = inst.label.trim()
            if (label.isNotEmpty()) labels.getOrPut(key) { ArrayList() }.add(label)
        }

        return counts.entries
            .map { (key, count) ->
                val seenTypes = types[key]?.toList().orEmpty()
                Suggestion(
                    url = display[key] ?: key,
                    useCount = count,
                    types = seenTypes,
                    usedByOtherType = forType != null && seenTypes.isNotEmpty() && forType !in seenTypes,
                    labels = labels[key]?.toList().orEmpty(),
                )
            }
            .sortedWith(compareByDescending<Suggestion> { it.useCount }.thenBy { it.url.lowercase() })
    }

    /**
     * Visible rows for the suggestion list: [query]-filtered, capped at [limit].
     *
     * Why a cap: the raw list is as long as the user's provider collection, and
     * an add-provider form that pushes its own Save button off-screen behind a
     * 17-row history is worse than no history. The UI scrolls within the cap
     * instead of growing, and the count of hidden rows is reported so the list
     * never silently truncates.
     *
     * Matching covers the URL and the labels of the instances using it, so
     * "gorouter" finds the host and "sonnet" finds the host that key sits on.
     */
    data class Filtered(
        val visible: List<Suggestion>,
        /** How many matched but did not fit in [limit]. */
        val hiddenCount: Int,
        /** Total matches, ignoring [limit]. */
        val matchCount: Int,
    )

    fun filter(
        suggestions: List<Suggestion>,
        query: String,
        limit: Int = DEFAULT_VISIBLE,
    ): Filtered {
        val matched = if (query.isBlank()) suggestions else suggestions.filter { s ->
            FuzzySearch.matchesAny(query, s.url, *s.labels.toTypedArray())
        }
        // limit <= 0 is treated as "no cap" rather than "show nothing": a caller
        // passing 0 by accident should not silently produce an empty list.
        val capped = if (limit <= 0) matched else matched.take(limit)
        return Filtered(
            visible = capped,
            hiddenCount = (matched.size - capped.size).coerceAtLeast(0),
            matchCount = matched.size,
        )
    }

    /**
     * Rows shown before scrolling. Five is a deliberate compromise: enough that
     * the common gateways are all visible at once, few enough that the endpoint
     * field and the form below it stay on screen.
     */
    const val DEFAULT_VISIBLE: Int = 5
}
