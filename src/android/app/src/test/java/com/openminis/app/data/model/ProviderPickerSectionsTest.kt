package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Folder grouping inside the chat model picker.
 */
class ProviderPickerSectionsTest {

    private fun inst(id: String, label: String, folder: String? = null) = ProviderInstance(
        id = id,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        folder = folder,
    )

    private fun counts(vararg pairs: Pair<String, Int>) = pairs.toMap()

    @Test
    fun `instances sharing a folder collapse into one block`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
            inst("3", "loose"),
        )
        val blocks = ProviderPickerSections.build(list, counts("1" to 3, "2" to 4, "3" to 2))
        assertEquals(2, blocks.size)
        val folder = blocks[0] as ProviderPickerSections.Block.Folder
        assertEquals("GoRouter", folder.name)
        assertEquals(listOf("1", "2"), folder.instances)
        // Header count is the sum, so a collapsed folder still says how much is inside.
        assertEquals(7, folder.entryCount)
        assertTrue(blocks[1] is ProviderPickerSections.Block.Loose)
    }

    @Test
    fun `single-instance folder is not given a nesting level`() {
        // Nesting one provider would cost a tap and buy no grouping.
        val list = listOf(inst("1", "only", folder = "Solo"))
        val blocks = ProviderPickerSections.build(list, counts("1" to 5))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ProviderPickerSections.Block.Loose)
        assertEquals("1", (blocks[0] as ProviderPickerSections.Block.Loose).instanceId)
    }

    @Test
    fun `instances filtered out by search do not appear`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
        )
        // Only one child survived the filter → folder degrades to a loose block.
        val blocks = ProviderPickerSections.build(list, counts("1" to 2))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is ProviderPickerSections.Block.Loose)
    }

    @Test
    fun `folder whose every child was filtered out disappears entirely`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
        )
        assertTrue(ProviderPickerSections.build(list, emptyMap()).isEmpty())
    }

    @Test
    fun `zero count is treated as absent`() {
        val list = listOf(inst("1", "k1"))
        assertTrue(ProviderPickerSections.build(list, counts("1" to 0)).isEmpty())
    }

    @Test
    fun `folders come before loose instances and keep alphabetical order`() {
        val list = listOf(
            inst("1", "loose"),
            inst("2", "z1", folder = "Zeta"),
            inst("3", "z2", folder = "Zeta"),
            inst("4", "a1", folder = "Alpha"),
            inst("5", "a2", folder = "Alpha"),
        )
        val blocks = ProviderPickerSections.build(
            list,
            counts("1" to 1, "2" to 1, "3" to 1, "4" to 1, "5" to 1),
        )
        assertEquals(
            listOf("Alpha", "Zeta"),
            blocks.filterIsInstance<ProviderPickerSections.Block.Folder>().map { it.name },
        )
        assertTrue(blocks.last() is ProviderPickerSections.Block.Loose)
    }

    @Test
    fun `folder spelling variants merge`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "gorouter "),
        )
        val blocks = ProviderPickerSections.build(list, counts("1" to 1, "2" to 1))
        assertEquals(1, blocks.size)
        assertEquals("GoRouter", (blocks[0] as ProviderPickerSections.Block.Folder).name)
    }

    @Test
    fun `block keys are unique and stable`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
            inst("3", "loose"),
        )
        val c = counts("1" to 1, "2" to 1, "3" to 1)
        val first = ProviderPickerSections.build(list, c).map { it.key }
        val second = ProviderPickerSections.build(list, c).map { it.key }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
    }

    @Test
    fun `picker folder keys cannot collide with provider list keys`() {
        // The two screens persist expansion separately; identical names must not
        // share one preference entry.
        assertFalse(
            ProviderPickerSections.folderKey("GoRouter") ==
                ProviderListSections.folderKey("GoRouter"),
        )
    }

    @Test
    fun `picker key collapses whitespace so it cannot inject the store delimiter`() {
        assertEquals("pickerFolder:go router", ProviderPickerSections.folderKey("Go  Router"))
        assertEquals("pickerFolder:go router", ProviderPickerSections.folderKey("Go\nRouter"))
    }

    @Test
    fun `folders are collapsed by default and forced open while searching`() {
        val list = listOf(
            inst("1", "k1", folder = "GoRouter"),
            inst("2", "k2", folder = "GoRouter"),
        )
        val folder = ProviderPickerSections
            .build(list, counts("1" to 1, "2" to 1))
            .filterIsInstance<ProviderPickerSections.Block.Folder>()
            .single()
        assertFalse(ProviderPickerSections.isFolderExpanded(folder, "", emptySet()))
        assertTrue(ProviderPickerSections.isFolderExpanded(folder, "gpt", emptySet()))
        assertTrue(ProviderPickerSections.isFolderExpanded(folder, "", setOf(folder.key)))
    }
}
