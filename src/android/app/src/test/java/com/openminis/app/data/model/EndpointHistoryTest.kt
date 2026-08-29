package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Endpoint suggestions mined from existing instances, and the
 * shared fuzzy matcher the search fields use.
 */
class EndpointHistoryTest {

    private fun inst(
        id: String,
        label: String,
        type: ProviderType = ProviderType.openAI,
        url: String? = null,
    ) = ProviderInstance(
        id = id,
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = url,
    )

    // ── normalize ───────────────────────────────────────────────────────────

    @Test
    fun `trailing slash and host case do not split one endpoint`() {
        val a = EndpointHistory.normalize("https://gorouter.app/")
        val b = EndpointHistory.normalize("https://GoRouter.app")
        val c = EndpointHistory.normalize("  https://gorouter.app  ")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `path case is preserved because relay paths can be case sensitive`() {
        // Lowercasing the whole URL would turn a working "/v1/Anthropic" relay
        // path into a 404.
        assertEquals(
            "https://relay.example.com/v1/Anthropic",
            EndpointHistory.normalize("https://Relay.Example.com/v1/Anthropic/"),
        )
    }

    @Test
    fun `blank and null yield null`() {
        assertEquals(null, EndpointHistory.normalize(null))
        assertEquals(null, EndpointHistory.normalize("   "))
        assertEquals(null, EndpointHistory.normalize("/"))
    }

    @Test
    fun `value without scheme is left alone`() {
        assertEquals("localhost:8080", EndpointHistory.normalize("localhost:8080/"))
    }

    // ── suggestions ─────────────────────────────────────────────────────────

    @Test
    fun `most used endpoint comes first`() {
        val list = listOf(
            inst("1", "a", url = "https://agentrouter.org"),
            inst("2", "b", url = "https://gorouter.app"),
            inst("3", "c", url = "https://gorouter.app/"),
            inst("4", "d", url = "https://gorouter.app"),
        )
        val s = EndpointHistory.suggestions(list)
        assertEquals(2, s.size)
        assertEquals("https://gorouter.app", s[0].url)
        assertEquals(3, s[0].useCount)
        assertEquals("https://agentrouter.org", s[1].url)
    }

    @Test
    fun `equal counts fall back to alphabetical for a stable order`() {
        val list = listOf(
            inst("1", "a", url = "https://zeta.example"),
            inst("2", "b", url = "https://alpha.example"),
        )
        val urls = EndpointHistory.suggestions(list).map { it.url }
        assertEquals(listOf("https://alpha.example", "https://zeta.example"), urls)
    }

    @Test
    fun `first spelling the user typed is what gets shown`() {
        val list = listOf(
            inst("1", "a", url = "https://GoRouter.app"),
            inst("2", "b", url = "https://gorouter.app"),
        )
        assertEquals("https://GoRouter.app", EndpointHistory.suggestions(list).single().url)
    }

    @Test
    fun `endpoint seen only on another provider type is flagged`() {
        val list = listOf(
            inst("1", "a", type = ProviderType.anthropic, url = "https://relay.example"),
        )
        val forOpenAI = EndpointHistory.suggestions(list, ProviderType.openAI).single()
        assertTrue(forOpenAI.usedByOtherType)
        val forAnthropic = EndpointHistory.suggestions(list, ProviderType.anthropic).single()
        assertFalse(forAnthropic.usedByOtherType)
    }

    @Test
    fun `gateway serving two surfaces is not flagged for either`() {
        val list = listOf(
            inst("1", "a", type = ProviderType.anthropic, url = "https://gorouter.app"),
            inst("2", "b", type = ProviderType.openAI, url = "https://gorouter.app"),
        )
        assertFalse(EndpointHistory.suggestions(list, ProviderType.openAI).single().usedByOtherType)
        assertFalse(EndpointHistory.suggestions(list, ProviderType.anthropic).single().usedByOtherType)
    }

    @Test
    fun `no type context flags nothing`() {
        val list = listOf(inst("1", "a", type = ProviderType.anthropic, url = "https://relay.example"))
        assertFalse(EndpointHistory.suggestions(list, null).single().usedByOtherType)
    }

    @Test
    fun `instances without a custom endpoint contribute nothing`() {
        val list = listOf(inst("1", "a", url = null), inst("2", "b", url = "  "))
        assertTrue(EndpointHistory.suggestions(list).isEmpty())
    }

    @Test
    fun `labels and types are collected for the row subtitle`() {
        val list = listOf(
            inst("1", "GoRouter key 1", type = ProviderType.openAI, url = "https://gorouter.app"),
            inst("2", "GoRouter key 2", type = ProviderType.anthropic, url = "https://gorouter.app"),
        )
        val s = EndpointHistory.suggestions(list).single()
        assertEquals(listOf("GoRouter key 1", "GoRouter key 2"), s.labels)
        assertEquals(listOf(ProviderType.openAI, ProviderType.anthropic), s.types)
    }

    // ── FuzzySearch ─────────────────────────────────────────────────────────

    @Test
    fun `substring match wins`() {
        assertTrue(FuzzySearch.matches("GoRouter Claude", "claude"))
        assertTrue(FuzzySearch.matches("gpt-4o-mini", "4o"))
    }

    @Test
    fun `subsequence match works out of order chars but in sequence`() {
        assertTrue(FuzzySearch.matches("gpt-router-4", "gr4"))
        assertFalse(FuzzySearch.matches("gpt-router-4", "4rg"))
    }

    @Test
    fun `empty query matches everything`() {
        assertTrue(FuzzySearch.matches("anything", ""))
        assertTrue(FuzzySearch.matchesAny("", null))
    }

    @Test
    fun `matchesAny checks every field and tolerates nulls`() {
        assertTrue(FuzzySearch.matchesAny("anthro", "my key", "Anthropic", null))
        assertFalse(FuzzySearch.matchesAny("zzz", "my key", "Anthropic", null))
    }
}
