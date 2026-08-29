package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Chat model picker sections.
 *
 * The behaviour that matters most here: the picker and the settings provider
 * list must answer "where does this provider live" identically. A provider in no
 * folder belongs under its provider type on BOTH screens.
 */
class ProviderPickerSectionsTest {

    private fun inst(
        id: String,
        label: String,
        folder: String? = null,
        type: ProviderType = ProviderType.openAI,
    ) = ProviderInstance(
        id = id,
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        folder = folder,
    )

    private fun counts(vararg pairs: Pair<String, Int>) = pairs.toMap()

    @Test
    fun `ungrouped providers are filed under their provider type, like the settings list`() {
        val list = listOf(
            inst("1", "key A"),
            inst("2", "key B"),
            inst("3", "claude", type = ProviderType.anthropic),
        )
        val s = ProviderPickerSections.build(list, counts("1" to 2, "2" to 3, "3" to 1))
        assertEquals(listOf("OpenAI", "Anthropic"), s.map { it.title })
        assertEquals(ProviderListSections.Kind.TYPE, s[0].kind)
        assertEquals(listOf("1", "2"), s[0].instanceIds)
        assertEquals(5, s[0].entryCount)
        // The type is carried so the header can wear the brand colour.
        assertEquals(ProviderType.openAI, s[0].type)
        assertEquals(ProviderType.anthropic, s[1].type)
    }

    @Test
    fun `grouping matches the settings list exactly`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
            inst("3", "loose"),
            inst("4", "claude", type = ProviderType.anthropic),
        )
        val counts = counts("1" to 1, "2" to 1, "3" to 1, "4" to 1)
        val picker = ProviderPickerSections.build(list, counts).map { it.title to it.kind }
        val settings = ProviderListSections.build(list).map { it.title to it.kind }
        assertEquals(settings, picker)
    }

    @Test
    fun `folders keep a nesting level even with one member`() {
        // Earlier this degraded a single-member folder into a bare card, which
        // made the same provider appear in a folder on one screen and loose on
        // the other. Consistency with the settings list wins.
        val list = listOf(inst("1", "only", folder = "Solo"))
        val s = ProviderPickerSections.build(list, counts("1" to 5))
        assertEquals(1, s.size)
        assertEquals("Solo", s[0].title)
        assertEquals(ProviderListSections.Kind.FOLDER, s[0].kind)
    }

    @Test
    fun `entry counts sum across members and drop empty instances`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
        )
        // Only one child survived the search filter.
        val s = ProviderPickerSections.build(list, counts("1" to 2))
        assertEquals(1, s.size)
        assertEquals(listOf("1"), s[0].instanceIds)
        assertEquals(2, s[0].entryCount)
    }

    @Test
    fun `section whose every member was filtered out disappears`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
        )
        assertTrue(ProviderPickerSections.build(list, emptyMap()).isEmpty())
    }

    @Test
    fun `zero count is treated as absent`() {
        assertTrue(ProviderPickerSections.build(listOf(inst("1", "k")), counts("1" to 0)).isEmpty())
    }

    @Test
    fun `picker keys are namespaced away from the settings list keys`() {
        val list = listOf(inst("1", "k1", folder = "GoRouter"), inst("2", "k2", folder = "GoRouter"))
        val picker = ProviderPickerSections.build(list, counts("1" to 1, "2" to 1)).single()
        val settings = ProviderListSections.build(list).single()
        assertFalse(picker.key == settings.key)
        assertEquals("picker:" + settings.key, picker.key)
        // Opening a folder in settings must not open it in the picker.
        assertFalse(ProviderPickerSections.isExpanded(picker, "", setOf(settings.key)))
    }

    @Test
    fun `folderKey agrees with build`() {
        val list = listOf(inst("1", "k1", folder = "Go  Router"), inst("2", "k2", folder = "go router"))
        val built = ProviderPickerSections.build(list, counts("1" to 1, "2" to 1)).single()
        assertEquals(built.key, ProviderPickerSections.folderKey("GO ROUTER"))
    }

    @Test
    fun `collapsed by default, opened by search or by the user`() {
        val list = listOf(inst("1", "k1", folder = "GoRouter"), inst("2", "k2", folder = "GoRouter"))
        val s = ProviderPickerSections.build(list, counts("1" to 1, "2" to 1)).single()
        assertFalse(ProviderPickerSections.isExpanded(s, "", emptySet()))
        assertTrue(ProviderPickerSections.isExpanded(s, "gpt", emptySet()))
        assertTrue(ProviderPickerSections.isExpanded(s, "", setOf(s.key)))
    }

    @Test
    fun `the section holding the active model opens itself`() {
        // Opening the picker onto a wall of closed headers, with no clue which
        // one holds the current model, is worse than the flat list it replaced.
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
            inst("3", "other", folder = "Elsewhere"),
            inst("4", "other2", folder = "Elsewhere"),
        )
        val sections = ProviderPickerSections.build(
            list, counts("1" to 1, "2" to 1, "3" to 1, "4" to 1),
        )
        val auto = ProviderPickerSections.keysContaining(sections, "2")
        val go = sections.first { it.title == "GoRouter" }
        val other = sections.first { it.title == "Elsewhere" }
        assertEquals(setOf(go.key), auto)
        assertTrue(ProviderPickerSections.isExpanded(go, "", emptySet(), auto))
        assertFalse(ProviderPickerSections.isExpanded(other, "", emptySet(), auto))
    }

    @Test
    fun `keysContaining tolerates a null or unknown instance`() {
        val sections = ProviderPickerSections.build(listOf(inst("1", "k")), counts("1" to 1))
        assertTrue(ProviderPickerSections.keysContaining(sections, null).isEmpty())
        assertTrue(ProviderPickerSections.keysContaining(sections, "nope").isEmpty())
    }
}
