package com.openminis.app.ui.settings

import android.content.Context

/**
 * [T-provider-ux] Which provider-list sections the user has opened.
 *
 * Persisted rather than kept in `remember` because the list is a hub: the user
 * opens a folder, taps into a provider, edits a key, comes back — and with
 * in-memory state every folder would be shut again, which is worse than always
 * being open. One set of keys in the appearance prefs, written as a delimited
 * string since SharedPreferences string-sets have historically been unreliable
 * to observe.
 *
 * Keys come from `ProviderListSections.folderKey` / `typeKey`, so a folder named
 * "openai" cannot collide with the OpenAI type section.
 */
object ProviderListPrefs {

    private const val KEY_EXPANDED = "providerList.expandedSections"

    /** `\n` cannot occur in a section key (folder names are trimmed one-liners). */
    private const val SEP = "\n"

    fun expandedKeys(context: Context): Set<String> =
        getAppearancePrefs(context)
            .getString(KEY_EXPANDED, "")
            .orEmpty()
            .split(SEP)
            .filter { it.isNotBlank() }
            .toSet()

    fun setExpanded(context: Context, key: String, expanded: Boolean) {
        // A key containing the delimiter would corrupt every other entry on the
        // next read. Callers derive keys from folderKey()/typeKey(), which
        // collapse whitespace, so this is a belt-and-braces guard rather than an
        // expected path.
        val safe = key.replace(SEP, " ")
        val current = expandedKeys(context).toMutableSet()
        if (expanded) current.add(safe) else current.remove(safe)
        getAppearancePrefs(context).edit()
            .putString(KEY_EXPANDED, current.joinToString(SEP))
            .apply()
    }
}
