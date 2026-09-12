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

    // ── filter / cap ────────────────────────────────────────────────────────

    @Test
    fun `filter caps the visible rows and reports what was hidden`() {
        val list = (1..9).map { inst("$it", "k$it", url = "https://h$it.example") }
        val all = EndpointHistory.suggestions(list)
        assertEquals(9, all.size)
        val f = EndpointHistory.filter(all, "", limit = 5)
        assertEquals(5, f.visible.size)
        assertEquals(4, f.hiddenCount)
        assertEquals(9, f.matchCount)
    }

    @Test
    fun `filter keeps the most-used rows, not an arbitrary five`() {
        val list = listOf(
            inst("1", "a", url = "https://rare.example"),
            inst("2", "b", url = "https://common.example"),
            inst("3", "c", url = "https://common.example"),
        )
        val f = EndpointHistory.filter(EndpointHistory.suggestions(list), "", limit = 1)
        assertEquals("https://common.example", f.visible.single().url)
    }

    @Test
    fun `filter matches the url`() {
        val list = listOf(
            inst("1", "a", url = "https://gorouter.app"),
            inst("2", "b", url = "https://agentrouter.org"),
        )
        val f = EndpointHistory.filter(EndpointHistory.suggestions(list), "gorou")
        assertEquals("https://gorouter.app", f.visible.single().url)
        assertEquals(0, f.hiddenCount)
    }

    @Test
    fun `filter also matches the labels of instances using the endpoint`() {
        // Searching the key you remember should find the host it sits on.
        val list = listOf(
            inst("1", "Sonnet main", url = "https://gorouter.app"),
            inst("2", "other", url = "https://elsewhere.example"),
        )
        val f = EndpointHistory.filter(EndpointHistory.suggestions(list), "sonnet")
        assertEquals("https://gorouter.app", f.visible.single().url)
    }

    @Test
    fun `filter with no matches yields an empty visible list, not everything`() {
        val list = listOf(inst("1", "a", url = "https://gorouter.app"))
        val f = EndpointHistory.filter(EndpointHistory.suggestions(list), "zzzzz")
        assertTrue(f.visible.isEmpty())
        assertEquals(0, f.matchCount)
        assertEquals(0, f.hiddenCount)
    }

    @Test
    fun `blank query is not treated as a filter`() {
        val list = listOf(inst("1", "a", url = "https://gorouter.app"))
        assertEquals(1, EndpointHistory.filter(EndpointHistory.suggestions(list), "   ").matchCount)
    }

    @Test
    fun `non-positive limit means no cap rather than an empty list`() {
        // A caller passing 0 by accident should not silently hide the history.
        val list = (1..7).map { inst("$it", "k$it", url = "https://h$it.example") }
        val all = EndpointHistory.suggestions(list)
        assertEquals(7, EndpointHistory.filter(all, "", limit = 0).visible.size)
        assertEquals(7, EndpointHistory.filter(all, "", limit = -3).visible.size)
    }

    @Test
    fun `hiddenCount is never negative when fewer rows than the cap`() {
        val list = listOf(inst("1", "a", url = "https://gorouter.app"))
        val f = EndpointHistory.filter(EndpointHistory.suggestions(list), "", limit = 5)
        assertEquals(0, f.hiddenCount)
    }

    // ── inline completion ───────────────────────────────────────────────────

    private fun completionFixture() = EndpointHistory.suggestions(
        listOf(
            inst("1", "tabi key", url = "https://tabitoken.com/v1"),
            inst("2", "go key", url = "https://gorouter.app"),
            inst("3", "agent key", url = "https://agentrouter.org"),
            inst("4", "openai", url = "https://api.openai.com"),
        ),
    )

    @Test
    fun `typing a host prefix completes the full endpoint`() {
        val c = EndpointHistory.complete(completionFixture(), "tab")
        assertEquals(listOf("https://tabitoken.com/v1"), c.map { it.url })
    }

    @Test
    fun `completion ignores the scheme the user has not typed`() {
        // Nobody types "https://" before the host they already know.
        assertEquals(
            listOf("https://gorouter.app"),
            EndpointHistory.complete(completionFixture(), "goroute").map { it.url },
        )
    }

    @Test
    fun `completion also matches when the scheme IS typed`() {
        assertEquals(
            listOf("https://agentrouter.org"),
            EndpointHistory.complete(completionFixture(), "https://agent").map { it.url },
        )
    }

    @Test
    fun `completion is prefix-based, not fuzzy`() {
        // THE point of a separate function: fuzzy would offer api.openai.com for
        // "ai", and a wrong base URL fails as an auth error, which sends the user
        // hunting the wrong problem.
        assertTrue(EndpointHistory.complete(completionFixture(), "ai").isEmpty())
        assertTrue(EndpointHistory.complete(completionFixture(), "router").isEmpty())
    }

    @Test
    fun `an exact match offers nothing`() {
        // There is no completion to offer for something already typed in full;
        // leaving the row up makes the user dismiss a no-op.
        assertTrue(
            EndpointHistory.complete(completionFixture(), "https://gorouter.app").isEmpty(),
        )
        // Trailing slash and case are the same URL, so also nothing.
        assertTrue(
            EndpointHistory.complete(completionFixture(), " HTTPS://GoRouter.app/ ").isEmpty(),
        )
    }

    @Test
    fun `an empty or blank query offers nothing`() {
        // Unlike filter(), where blank means "show the whole history".
        assertTrue(EndpointHistory.complete(completionFixture(), "").isEmpty())
        assertTrue(EndpointHistory.complete(completionFixture(), "   ").isEmpty())
    }

    @Test
    fun `completion respects the row limit and keeps frequency order`() {
        val list = listOf(
            inst("1", "a", url = "https://tabitoken.com/v1"),
            inst("2", "b", url = "https://tabitoken.com/v1"),
            inst("3", "c", url = "https://tabix.dev"),
            inst("4", "d", url = "https://tabi-relay.net"),
            inst("5", "e", url = "https://tabi.example"),
            inst("6", "f", url = "https://tabiz.io"),
        )
        val all = EndpointHistory.complete(EndpointHistory.suggestions(list), "tab", limit = 0)
        assertEquals(5, all.size)
        // Most-used first — suggestions() already sorted, complete() must not
        // reshuffle.
        assertEquals("https://tabitoken.com/v1", all.first().url)
        assertEquals(2, EndpointHistory.complete(EndpointHistory.suggestions(list), "tab", limit = 2).size)
    }

    @Test
    fun `www prefix is transparent to completion`() {
        val list = listOf(inst("1", "a", url = "https://www.relay.example/v1"))
        val s = EndpointHistory.suggestions(list)
        assertEquals(1, EndpointHistory.complete(s, "relay").size)
        assertEquals(1, EndpointHistory.complete(s, "www.rel").size)
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
