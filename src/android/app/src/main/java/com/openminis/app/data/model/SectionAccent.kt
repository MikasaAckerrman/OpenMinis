package com.openminis.app.data.model

/**
 * [T-provider-ux] Which accent a section header wears.
 *
 * The problem this solves: with folders and provider-type groups rendered in the
 * same neutral grey, a screen of collapsed headers gives no clue what kind of
 * thing you are looking at or which header owns the card below it. Colour is the
 * cheapest signal that survives being glanced at.
 *
 * Rules:
 *  - A TYPE section takes its provider's brand colour, because that is already
 *    the colour of the dots on the rows inside it. Header and contents visibly
 *    belong together.
 *  - A FOLDER section takes a colour from a fixed palette, chosen by a stable
 *    hash of the folder's identity. Folders are user-named, so there is nothing
 *    to derive a meaning from — but the same folder must keep the same colour
 *    across launches and across both screens, which rules out list position.
 *  - The folder palette deliberately EXCLUDES the provider brand hues. A folder
 *    tinted the same green as OpenAI would read as "this is the OpenAI group",
 *    which is the confusion we are removing.
 *
 * Returned as ARGB Int so this stays a pure module with no Compose dependency
 * and can be unit-tested; the UI wraps it in Color(...).
 */
object SectionAccent {

    /**
     * Folder hues, none of which collide with a provider brand colour
     * (green #4CAF50, purple #AB47BC, blue #42A5F5, cyan #00BCD4,
     * orange #FF7043, indigo #5C6BC0).
     */
    val FOLDER_PALETTE: List<Int> = listOf(
        0xFFEC407A.toInt(), // pink
        0xFFFFB300.toInt(), // amber
        0xFF26A69A.toInt(), // teal
        0xFF7E57C2.toInt(), // deep purple
        0xFF8D6E63.toInt(), // brown
        0xFF9CCC65.toInt(), // light green
        0xFFD4E157.toInt(), // lime
        0xFF29B6F6.toInt(), // light blue
    )

    /** Grey for "no provider type" — mirrors the existing dot colour. */
    const val NEUTRAL: Int = 0xFF8E8E93.toInt()

    fun providerTypeColor(type: ProviderType?): Int = when (type) {
        ProviderType.anthropic -> 0xFFAB47BC.toInt()
        ProviderType.gemini -> 0xFF42A5F5.toInt()
        ProviderType.openAI -> 0xFF4CAF50.toInt()
        ProviderType.openRouter -> 0xFF00BCD4.toInt()
        ProviderType.xAI -> 0xFFFF7043.toInt()
        ProviderType.kimiCode -> 0xFF5C6BC0.toInt()
        null -> NEUTRAL
    }

    /**
     * Stable palette colour for a folder.
     *
     * The hash runs over the case-folded, whitespace-collapsed name so folder
     * spelling variants that [ProviderFolders] already merges into one folder
     * also share one colour. String.hashCode is specified by the JDK, so the
     * mapping is stable across processes and releases — unlike identity hashes
     * or list indices.
     */
    fun folderColor(name: String): Int {
        // Same identity rule as folder grouping, so spelling variants that
        // ProviderFolders merges into one folder also share one colour.
        val id = ProviderFolders.key(name) ?: return NEUTRAL
        // Math.abs(Int.MIN_VALUE) is negative, so fold via toLong() first.
        val idx = (id.hashCode().toLong().let { if (it < 0) -it else it } %
            FOLDER_PALETTE.size).toInt()
        return FOLDER_PALETTE[idx]
    }

    /**
     * Accent for a section as laid out by [ProviderListSections].
     *
     * @param typeForKey the provider type of a TYPE section. Ignored for folders.
     */
    fun forSection(
        kind: ProviderListSections.Kind,
        title: String,
        typeForKey: ProviderType?,
    ): Int = when (kind) {
        ProviderListSections.Kind.FOLDER -> folderColor(title)
        ProviderListSections.Kind.TYPE -> providerTypeColor(typeForKey)
    }
}
