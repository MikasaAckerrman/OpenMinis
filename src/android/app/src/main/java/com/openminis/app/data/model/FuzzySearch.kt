package com.openminis.app.data.model

/**
 * [T-provider-ux] Fuzzy text matching shared by every list that has a search
 * field: the chat model picker and the provider list.
 *
 * It lived as a private helper inside ChatModelPickerSheet, which meant the
 * provider list could not reuse it — the alternative was a second copy that
 * would silently drift (one screen matching "gr4" to "gpt-4", the other not).
 * Same rules as iOS SessionModelPicker.fuzzyMatch so search behaves identically
 * across platforms.
 */
object FuzzySearch {

    /**
     * True when [query] matches [text]: substring first (the common case), then
     * subsequence — every query char appears in [text] in order, so "gr4"
     * matches "gpt-router-4".
     *
     * An empty query matches everything, which is what a search field with no
     * input should do.
     */
    fun matches(text: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        val t = text.lowercase()
        if (t.contains(q)) return true
        var idx = 0
        for (ch in q) {
            val found = t.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }

    /**
     * True when [query] matches ANY of [texts]. Callers routinely need to match
     * a row against several fields (provider label, its type name, its folder);
     * doing that with repeated `matches(a, q) || matches(b, q)` reads worse and
     * invites forgetting a field.
     */
    fun matchesAny(query: String, vararg texts: String?): Boolean {
        if (query.isEmpty()) return true
        return texts.any { it != null && matches(it, query) }
    }
}
