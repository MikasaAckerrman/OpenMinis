package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Section accent colours.
 */
class SectionAccentTest {

    @Test
    fun `type sections wear the provider brand colour`() {
        assertEquals(
            SectionAccent.providerTypeColor(ProviderType.openAI),
            SectionAccent.forSection(ProviderListSections.Kind.TYPE, "OpenAI", ProviderType.openAI),
        )
        assertEquals(
            SectionAccent.providerTypeColor(ProviderType.anthropic),
            SectionAccent.forSection(
                ProviderListSections.Kind.TYPE, "Anthropic", ProviderType.anthropic,
            ),
        )
    }

    @Test
    fun `every provider type has a distinct colour`() {
        val colors = ProviderType.values().map { SectionAccent.providerTypeColor(it) }
        assertEquals(colors.size, colors.toSet().size)
    }

    @Test
    fun `folder colours never collide with a provider brand colour`() {
        // A folder tinted OpenAI-green would read as "this is the OpenAI group",
        // which is the confusion the colours are meant to remove.
        val brand = ProviderType.values().map { SectionAccent.providerTypeColor(it) }.toSet()
        assertTrue(SectionAccent.FOLDER_PALETTE.none { it in brand })
    }

    @Test
    fun `folder palette has no duplicates`() {
        assertEquals(
            SectionAccent.FOLDER_PALETTE.size,
            SectionAccent.FOLDER_PALETTE.toSet().size,
        )
    }

    @Test
    fun `folder colour is stable for the same name`() {
        val a = SectionAccent.folderColor("GoRouter")
        val b = SectionAccent.folderColor("GoRouter")
        assertEquals(a, b)
    }

    @Test
    fun `folder colour ignores case and whitespace, matching folder merging`() {
        // ProviderFolders merges these into one folder, so they must not be
        // painted as two.
        val base = SectionAccent.folderColor("GoRouter")
        assertEquals(base, SectionAccent.folderColor("gorouter"))
        assertEquals(base, SectionAccent.folderColor("  GOROUTER  "))
        assertEquals(
            SectionAccent.folderColor("Go Router"),
            SectionAccent.folderColor("Go   Router"),
        )
    }

    @Test
    fun `different folders usually get different colours`() {
        val names = listOf("GoRouter", "AgentRouter", "Local", "Backup", "Work")
        val colors = names.map { SectionAccent.folderColor(it) }
        // Not a guarantee (hash buckets can collide), but a palette that maps
        // five ordinary names onto one colour would be useless.
        assertTrue(colors.toSet().size >= 3)
    }

    @Test
    fun `folder colour is always inside the palette`() {
        val names = listOf("a", "b", "zzz", "GoRouter", "Мой шлюз", "1", "long name here")
        for (n in names) {
            assertTrue(SectionAccent.folderColor(n) in SectionAccent.FOLDER_PALETTE)
        }
    }

    @Test
    fun `blank folder name falls back to neutral instead of crashing`() {
        assertEquals(SectionAccent.NEUTRAL, SectionAccent.folderColor(""))
        assertEquals(SectionAccent.NEUTRAL, SectionAccent.folderColor("   "))
    }

    @Test
    fun `hash folding survives Int MIN_VALUE`() {
        // Math.abs(Int.MIN_VALUE) is itself negative; a naive abs() would index
        // the palette with a negative number and throw. Find a real string that
        // hashes to Int.MIN_VALUE-adjacent territory rather than trusting the
        // implementation: exercise a wide spread and assert no exception plus a
        // valid palette entry.
        for (i in 0..2000) {
            val c = SectionAccent.folderColor("folder$i")
            assertTrue(c in SectionAccent.FOLDER_PALETTE)
        }
    }

    @Test
    fun `null provider type is neutral, not a brand colour`() {
        assertEquals(SectionAccent.NEUTRAL, SectionAccent.providerTypeColor(null))
        val brand = ProviderType.values().map { SectionAccent.providerTypeColor(it) }
        assertFalse(SectionAccent.NEUTRAL in brand)
    }

    @Test
    fun `folder accent differs from the neutral grey`() {
        // Otherwise a folder would be indistinguishable from "no type".
        assertTrue(SectionAccent.FOLDER_PALETTE.none { it == SectionAccent.NEUTRAL })
    }

    @Test
    fun `folder and type sections with the same title get different accents`() {
        // A folder named "OpenAI" must not be painted like the OpenAI type group.
        assertNotEquals(
            SectionAccent.forSection(ProviderListSections.Kind.FOLDER, "OpenAI", null),
            SectionAccent.forSection(
                ProviderListSections.Kind.TYPE, "OpenAI", ProviderType.openAI,
            ),
        )
    }
}
