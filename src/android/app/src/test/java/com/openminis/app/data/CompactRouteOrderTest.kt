package com.openminis.app.data

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactRouteOrderTest {

    private fun provider(id: String, ctx: Int? = null): LLMProvider = object : LLMProvider {
        override val name: String = id
        override var model: LLMModel = LLMModel(
            id = id,
            displayName = id,
            provider = "inst",
            contextWindow = ctx,
        )

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = error("unused in ordering tests")

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = error("unused in ordering tests")
    }

    @Test
    fun `fast tiers outrank heavy and neutral tiers`() {
        assertEquals(0, CompactRouteOrder.speedRank("deepseek-v4-flash-0731:free"))
        assertEquals(0, CompactRouteOrder.speedRank("glm-5.3-flash"))
        assertEquals(0, CompactRouteOrder.speedRank("MiniMax-M2.5-highspeed"))
        assertEquals(0, CompactRouteOrder.speedRank("ministral-14b-latest"))
        assertEquals(0, CompactRouteOrder.speedRank("mimo-v2.6-flash"))
        assertEquals(0, CompactRouteOrder.speedRank("qwen3.8-27b:free"))
        assertEquals(8, CompactRouteOrder.speedRank("claude-opus-5"))
        assertEquals(8, CompactRouteOrder.speedRank("qwen3.8-max"))
        assertEquals(8, CompactRouteOrder.speedRank("mimo-v2.6-pro"))
        assertEquals(5, CompactRouteOrder.speedRank("gpt-5.6-sol"))
        assertEquals(5, CompactRouteOrder.speedRank("glm-5.3"))
    }

    @Test
    fun `map phase starts on fast tiers, heavy last, full set preserved`() {
        val routes = listOf(
            provider("glm-5.3"),
            provider("claude-opus-5"),
            provider("deepseek-v4-flash"),
            provider("gpt-5.6-sol"),
        )
        val ordered = CompactRouteOrder.orderRoutes(routes, maxWindowChars = 12_000)
        assertEquals("deepseek-v4-flash", ordered.first().model.id)
        assertEquals("claude-opus-5", ordered.last().model.id)
        assertEquals(
            routes.map { it.model.id }.toSet(),
            ordered.map { it.model.id }.toSet(),
        )
    }

    @Test
    fun `tiny-context route demotes to the end but stays usable`() {
        val routes = listOf(
            provider("slow-heavy-pro", ctx = 262_144),
            provider("deepseek-v4-flash", ctx = 8_192),
        )
        // 96K chars ≈ 24K tokens + 2K output — over the 8K-context route.
        val ordered = CompactRouteOrder.orderRoutes(routes, maxWindowChars = 96_000)
        assertEquals(2, ordered.size)
        assertEquals("slow-heavy-pro", ordered.first().model.id)
        assertEquals("deepseek-v4-flash", ordered.last().model.id)
    }

    @Test
    fun `unknown context keeps the route at its speed tier`() {
        val routes = listOf(
            provider("claude-opus-5", ctx = null),
            provider("deepseek-v4-flash", ctx = null),
        )
        val ordered = CompactRouteOrder.orderRoutes(routes, maxWindowChars = 96_000)
        assertEquals("deepseek-v4-flash", ordered.first().model.id)
    }

    @Test
    fun `ordering is stable inside a tier`() {
        val routes = listOf(
            provider("deepseek-a-flash"),
            provider("ministral-flash"),
            provider("mimo-flash"),
        )
        val ordered = CompactRouteOrder.orderRoutes(routes, maxWindowChars = 10_000)
        assertEquals(
            listOf("deepseek-a-flash", "ministral-flash", "mimo-flash"),
            ordered.map { it.model.id },
        )
    }
}
