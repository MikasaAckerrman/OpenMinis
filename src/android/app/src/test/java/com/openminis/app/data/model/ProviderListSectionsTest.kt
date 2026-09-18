package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Section layout + search + collapse rules for the provider list.
 */
class ProviderListSectionsTest {

    private fun inst(
        id: String,
        label: String,
        type: ProviderType = ProviderType.openAI,
        folder: String? = null,
        url: String? = null,
    ) = ProviderInstance(
        id = id,
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        folder = folder,
        customBaseURL = url,
    )

    private val sample = listOf(
        inst("1", "Key one", folder = "GoRouter", url = "https://gorouter.app"),
        inst("2", "Key two", folder = "gorouter ", url = "https://gorouter.app"),
        inst("3", "Agent key", folder = "AgentRouter", url = "https://agentrouter.org"),
        inst("4", "Plain OpenAI"),
        inst("5", "Claude direct", type = ProviderType.anthropic),
    )

    // ── layout ──────────────────────────────────────────────────────────────

    @Test
    fun `folders come first then type sections`() {
        val s = ProviderListSections.build(sample)
        assertEquals(
            listOf("AgentRouter", "GoRouter", "OpenAI", "Anthropic"),
            s.map { it.title },
        )
        assertEquals(
            listOf(
                ProviderListSections.Kind.FOLDER,
                ProviderListSections.Kind.FOLDER,
                ProviderListSections.Kind.TYPE,
                ProviderListSections.Kind.TYPE,
            ),
            s.map { it.kind },
        )
    }

    @Test
    fun `folder spelling variants merge into one section`() {
        val s = ProviderListSections.build(sample).single { it.title == "GoRouter" }
        assertEquals(2, s.instances.size)
    }

    @Test
    fun `folder named like a type does not collide with the type section`() {
        // Both sections exist and keep separate collapse state.
        val list = listOf(
            inst("1", "in folder", folder = "openai"),
            inst("2", "ungrouped"),
        )
        val s = ProviderListSections.build(list)
        assertEquals(2, s.size)
        assertEquals(2, s.map { it.key }.toSet().size)
        assertEquals("folder:openai", s[0].key)
        assertEquals("type:openAI", s[1].key)
    }

    @Test
    fun `section key collapses whitespace so it cannot inject the store delimiter`() {
        // The expanded-keys store is newline-delimited, and a folder name can
        // arrive through provider import where nothing strips control chars.
        assertEquals("folder:go router", ProviderListSections.folderKey("Go  Router"))
        assertEquals("folder:go router", ProviderListSections.folderKey("Go\nRouter"))
        assertEquals("folder:go router", ProviderListSections.folderKey("  Go\tRouter  "))
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(ProviderListSections.build(emptyList()).isEmpty())
    }

    // ── search ──────────────────────────────────────────────────────────────

    @Test
    fun `search by folder name keeps every child`() {
        val s = ProviderListSections.build(sample, "gorouter")
        assertEquals(1, s.size)
        assertEquals("GoRouter", s[0].title)
        assertEquals(2, s[0].instances.size)
        assertTrue(s[0].matchedByTitle)
    }

    @Test
    fun `search by instance label keeps only matching children`() {
        val s = ProviderListSections.build(sample, "Key one")
        assertEquals(1, s.size)
        assertEquals(listOf("Key one"), s[0].instances.map { it.label })
        assertFalse(s[0].matchedByTitle)
    }

    @Test
    fun `search matches the endpoint because same-named relay keys differ only by URL`() {
        val s = ProviderListSections.build(sample, "agentrouter.org")
        assertEquals(1, s.size)
        assertEquals(listOf("Agent key"), s[0].instances.map { it.label })
    }

    @Test
    fun `search by provider type name keeps the type section`() {
        val s = ProviderListSections.build(sample, "Anthropic")
        assertEquals(listOf("Anthropic"), s.map { it.title })
    }

    @Test
    fun `sections that match nothing are dropped not left as bare headers`() {
        val s = ProviderListSections.build(sample, "zzzz")
        assertTrue(s.isEmpty())
    }

    @Test
    fun `empty query returns everything and flags nothing as title-matched`() {
        val s = ProviderListSections.build(sample, "")
        assertEquals(4, s.size)
        assertTrue(s.none { it.matchedByTitle })
        assertEquals(5, ProviderListSections.instanceCount(s))
    }

    @Test
    fun `fuzzy subsequence reaches an instance label`() {
        val s = ProviderListSections.build(sample, "clddrct")
        assertEquals(listOf("Claude direct"), s.single().instances.map { it.label })
    }

    // ── collapse ────────────────────────────────────────────────────────────

    @Test
    fun `sections are collapsed by default`() {
        val s = ProviderListSections.build(sample)
        assertTrue(s.none { ProviderListSections.isExpanded(it, "", emptySet()) })
    }

    @Test
    fun `user expanded key opens exactly that section`() {
        val s = ProviderListSections.build(sample)
        val open = setOf(ProviderListSections.folderKey("GoRouter"))
        assertTrue(ProviderListSections.isExpanded(s.single { it.title == "GoRouter" }, "", open))
        assertFalse(ProviderListSections.isExpanded(s.single { it.title == "AgentRouter" }, "", open))
    }

    @Test
    fun `search forces sections open so results are never hidden in a closed folder`() {
        val s = ProviderListSections.build(sample, "key")
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { ProviderListSections.isExpanded(it, "key", emptySet()) })
    }

    @Test
    fun `count reflects filtering`() {
        assertEquals(2, ProviderListSections.instanceCount(ProviderListSections.build(sample, "gorouter")))
    }
}
