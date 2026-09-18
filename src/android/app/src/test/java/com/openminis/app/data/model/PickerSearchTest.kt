package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-ux] Picker search matches the PROVIDER, not only model names.
 */
class PickerSearchTest {

    private fun inst(
        label: String,
        folder: String? = null,
        type: ProviderType = ProviderType.openAI,
        url: String? = null,
    ) = ProviderInstance(
        id = label,
        label = label,
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        folder = folder,
        customBaseURL = url,
    )

    /** Stand-in for a model entry: display name + id. */
    private data class E(val name: String, val id: String)

    private val texts: (E) -> List<String?> = { listOf(it.name, it.id) }

    private val models = listOf(
        E("GPT-4o", "gpt-4o"),
        E("Claude Sonnet", "claude-3-5-sonnet"),
    )

    @Test
    fun `provider label match shows every model of that provider`() {
        // Searching the provider you are thinking of must not hide its models.
        val i = inst("GoRouter main")
        val v = PickerSearch.visibleEntries(i, models, "gorouter", texts)
        assertEquals(models, v)
    }

    @Test
    fun `provider folder, type and endpoint are searchable`() {
        assertTrue(PickerSearch.matchesProvider(inst("k", folder = "GoRouter"), "gorouter"))
        assertTrue(
            PickerSearch.matchesProvider(inst("k", type = ProviderType.anthropic), "anthropic"),
        )
        assertTrue(
            PickerSearch.matchesProvider(inst("k", url = "https://gorouter.app"), "gorouter.app"),
        )
    }

    @Test
    fun `model search still works when the provider does not match`() {
        val i = inst("GoRouter main")
        val v = PickerSearch.visibleEntries(i, models, "sonnet", texts)
        assertEquals(listOf(models[1]), v)
    }

    @Test
    fun `model id is searchable, not just the display name`() {
        val i = inst("p")
        assertEquals(
            listOf(models[1]),
            PickerSearch.visibleEntries(i, models, "claude-3-5", texts),
        )
    }

    @Test
    fun `fuzzy subsequence works on models`() {
        val i = inst("p")
        assertEquals(listOf(models[0]), PickerSearch.visibleEntries(i, models, "g4o", texts))
    }

    @Test
    fun `no match yields no entries`() {
        val i = inst("GoRouter")
        assertTrue(PickerSearch.visibleEntries(i, models, "zzzz", texts).isEmpty())
    }

    @Test
    fun `empty query returns everything untouched`() {
        val i = inst("p")
        assertEquals(models, PickerSearch.visibleEntries(i, models, "", texts))
    }

    @Test
    fun `credentials are not searchable`() {
        // The API key is not a field on ProviderInstance at all (it lives in the
        // keystore), so it cannot leak into search. What IS on the instance and
        // must stay out of matching is the custom User-Agent — it is a spoofing
        // detail, not an identity the user searches by.
        val i = ProviderInstance(
            id = "1",
            label = "k",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customUserAgent = "claude-cli/1.2.3 secretmarker",
        )
        assertFalse(PickerSearch.matchesProvider(i, "secretmarker"))
    }

    @Test
    fun `provider match is consistent with the settings list search`() {
        // Both screens must agree on what "matches this provider" means.
        val i = inst("GoRouter main", folder = "Gateways", url = "https://gorouter.app")
        for (q in listOf("gorouter", "gateways", "openai", "gorouter.app", "main")) {
            assertEquals(
                "query=$q",
                ProviderListSections.build(listOf(i), q).isNotEmpty(),
                PickerSearch.matchesProvider(i, q),
            )
        }
    }
}
