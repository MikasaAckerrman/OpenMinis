package com.openminis.app.offload

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.offload.AgentRoleModelResolver.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRoleModelResolverTest {
    private fun entry(id: String, output: Int? = 16_384) = ModelEntry(
        providerInstanceId = "p", baseModel = LLMModel(id, id, "test", output), uuid = id,
    )

    @Test
    fun `per-role setting beats shared default and current model`() {
        val r = AgentRoleModelResolver.resolve(
            perRoleEntry = entry("picked"),
            sharedDefaultEntry = entry("shared"),
            currentModelEntry = entry("current"),
            catalogCandidates = listOf(entry("cheap", 4_096)),
        )
        assertEquals("picked", r.entry?.id)
        assertEquals(Source.PER_ROLE_SETTING, r.source)
    }

    @Test
    fun `role api key wins over everything`() {
        val r = AgentRoleModelResolver.resolve(
            roleKeyEntry = entry("bykey"),
            perRoleEntry = entry("picked"),
            currentModelEntry = entry("current"),
        )
        assertEquals(Source.ROLE_KEY, r.source)
        assertEquals("bykey", r.entry?.id)
    }

    @Test
    fun `unconfigured role uses the model the user chats with, not the catalog`() {
        // The regression that sent agents to an unfunded provider.
        val r = AgentRoleModelResolver.resolve(
            currentModelEntry = entry("my-funded-model"),
            catalogCandidates = listOf(entry("random-catalog", 4_096)),
        )
        assertEquals(Source.CURRENT_MODEL, r.source)
        assertEquals("my-funded-model", r.entry?.id)
    }

    @Test
    fun `catalog fallback only when nothing else is known and picks smallest cap`() {
        val r = AgentRoleModelResolver.resolve(
            catalogCandidates = listOf(entry("big", 128_000), entry("small", 4_096)),
        )
        assertEquals(Source.CATALOG_FALLBACK, r.source)
        assertEquals("small", r.entry?.id)
    }

    @Test
    fun `no providers resolves to nothing`() {
        val r = AgentRoleModelResolver.resolve()
        assertNull(r.entry)
        assertEquals(Source.NONE, r.source)
    }
}
