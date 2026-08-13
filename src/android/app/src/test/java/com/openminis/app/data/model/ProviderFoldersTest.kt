package com.openminis.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-folders] Grouping rules for the provider list's user-defined
 * folders, plus the persistence-safety contract on the new
 * `ProviderInstance.folder` field.
 *
 * The failure modes worth guarding:
 *  - A spelling variant ("gorouter" vs "GoRouter") silently splitting one
 *    17-key folder into two — the whole point of the feature is fewer rows.
 *  - An instance vanishing from the list because it fell into neither the
 *    folder buckets nor the ungrouped remainder.
 *  - Old persisted JSON (written before this field existed) failing to decode,
 *    which would wipe every provider from the UI.
 */
class ProviderFoldersTest {

    private fun inst(
        label: String,
        folder: String? = null,
        type: ProviderType = ProviderType.openAI,
    ) = ProviderInstance(
        id = label,
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        folder = folder,
    )

    @Test
    fun noFolders_everythingUngroupedInConfigOrder() {
        val layout = ProviderFolders.layout(listOf(inst("a"), inst("b"), inst("c")))
        assertTrue(layout.folders.isEmpty())
        assertEquals(listOf("a", "b", "c"), layout.ungrouped.map { it.label })
    }

    @Test
    fun blankOrWhitespaceFolder_countsAsUngrouped() {
        val layout = ProviderFolders.layout(
            listOf(inst("a", ""), inst("b", "   "), inst("c", null)),
        )
        assertTrue(layout.folders.isEmpty())
        assertEquals(3, layout.ungrouped.size)
    }

    @Test
    fun caseAndWhitespaceVariants_mergeIntoOneFolder_firstSpellingWins() {
        val layout = ProviderFolders.layout(
            listOf(inst("k1", "GoRouter"), inst("k2", "gorouter "), inst("k3", " GOROUTER")),
        )
        assertEquals(1, layout.folders.size)
        assertEquals("GoRouter", layout.folders[0].name)
        assertEquals(3, layout.folders[0].instances.size)
    }

    @Test
    fun folders_sortedCaseInsensitively_membersKeepConfigOrder() {
        val layout = ProviderFolders.layout(
            listOf(
                inst("z1", "zulu"),
                inst("a1", "Alpha"),
                inst("z2", "zulu"),
                inst("m1", "mike"),
                inst("plain"),
            ),
        )
        assertEquals(listOf("Alpha", "mike", "zulu"), layout.folders.map { it.name })
        assertEquals(listOf("z1", "z2"), layout.folders.last().instances.map { it.label })
        assertEquals(listOf("plain"), layout.ungrouped.map { it.label })
    }

    @Test
    fun everyInstanceAppearsExactlyOnce() {
        val input = listOf(inst("a", "F1"), inst("b", "F2"), inst("c"), inst("d", "f1"), inst("e"))
        val layout = ProviderFolders.layout(input)
        val seen = layout.folders.flatMap { it.instances }.map { it.label } +
            layout.ungrouped.map { it.label }
        assertEquals(input.map { it.label }.sorted(), seen.sorted())
        assertEquals(seen.size, seen.distinct().size)
    }

    @Test
    fun existingNames_dedupedAndSorted() {
        val list = listOf(inst("a", "GoRouter"), inst("b", "gorouter"), inst("c", "Apim"), inst("d"))
        assertEquals(listOf("Apim", "GoRouter"), ProviderFolders.existingNames(list))
    }

    @Test
    fun normalizeAndKey_contract() {
        assertEquals("Go", ProviderFolders.normalize("  Go  "))
        assertNull(ProviderFolders.normalize("   "))
        assertNull(ProviderFolders.normalize(null))
        assertEquals("gorouter", ProviderFolders.key(" GoRouter "))
        assertNull(ProviderFolders.key(""))
    }

    /** The shape that motivated the feature: 17 keys of one relay + others. */
    @Test
    fun seventeenKeysCollapseToOneTopLevelRow() {
        val many = (1..17).map { inst("go$it", "GoRouter") }
        val rest = listOf(inst("apim1", "APIMaster"), inst("apim2", "APIMaster"), inst("solo"))
        val layout = ProviderFolders.layout(many + rest)

        assertEquals(17, layout.folders.first { it.name == "GoRouter" }.instances.size)
        // 20 instances → 2 folder headers + 1 loose row at the top level.
        assertEquals(2, layout.folders.size)
        assertEquals(1, layout.ungrouped.size)
    }

    /**
     * Persisted JSON written BEFORE the folder field existed must still decode —
     * the field is nullable with a declared default, so kotlinx.serialization
     * fills it in. A regression here empties the provider list on upgrade.
     */
    @Test
    fun legacyJsonWithoutFolderKey_decodesAsUngrouped() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        val legacy = """
            {"id":"i1","label":"Old","providerType":"openAI","credentialType":"apiKey",
             "isEnabled":true,"createdAt":1,"appendV1Suffix":true}
        """.trimIndent()
        val decoded = json.decodeFromString(ProviderInstance.serializer(), legacy)
        assertNull(decoded.folder)
        assertEquals("Old", decoded.label)
    }

    /** Round-trip: a named folder survives encode → decode unchanged. */
    @Test
    fun folderSurvivesJsonRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        val original = inst("k", "GoRouter")
        val restored = json.decodeFromString(
            ProviderInstance.serializer(),
            json.encodeToString(ProviderInstance.serializer(), original),
        )
        assertEquals("GoRouter", restored.folder)
    }
}
