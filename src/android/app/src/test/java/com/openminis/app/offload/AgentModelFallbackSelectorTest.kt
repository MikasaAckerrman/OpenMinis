package com.openminis.app.offload

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentModelFallbackSelectorTest {
    private fun entry(id: String, output: Int?): ModelEntry = ModelEntry(
        providerInstanceId = "provider",
        baseModel = LLMModel(id, id, "test", maxOutputTokens = output),
        uuid = id,
    )

    @Test
    fun `selects smallest known output cap instead of catalog order`() {
        val selected = AgentModelFallbackSelector.select(
            listOf(entry("opus", 128_000), entry("haiku", 64_000), entry("small", 4_096)),
        )
        assertEquals("small", selected?.id)
    }

    @Test
    fun `unknown cap loses to known cap and ties are deterministic`() {
        val selected = AgentModelFallbackSelector.select(
            listOf(entry("zeta", null), entry("beta", 16_384), entry("alpha", 16_384)),
        )
        assertEquals("alpha", selected?.id)
        assertNull(AgentModelFallbackSelector.select(emptyList()))
    }
}
