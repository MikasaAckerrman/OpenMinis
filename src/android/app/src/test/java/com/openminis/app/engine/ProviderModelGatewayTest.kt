package com.openminis.app.engine

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.provider.LLMProvider
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m8-gateway] The production adapter: provider chunks → engine
 * events, engine history → the provider message shape (byte-compatible
 * with the ViewModel loop's request construction).
 */
class ProviderModelGatewayTest {

    private class FakeProvider(
        vararg rounds: Flow<LLMStreamChunk>,
    ) : LLMProvider {
        private val script: List<Flow<LLMStreamChunk>> =
            (if (rounds.isEmpty()) listOf(flowOf()) else rounds.toList())
        private var index = 0
        override val name = "fake"
        override var model = com.openminis.app.data.model.LLMModel(
            id = "fake-model",
            displayName = "Fake",
            provider = "fake",
        )
        var seenMessages: List<LLMMessage> = emptyList()
            private set
        var lastImageParts: List<LLMMessage.ImagePart> = emptyList()
            private set
        var lastSystemPrompt: String? = null
            private set
        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> {
            seenMessages = messages
            lastImageParts = imageParts
            lastSystemPrompt = systemPrompt
            return script[index.coerceAtMost(script.size - 1)].also { index++ }
        }

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = throw UnsupportedOperationException()
    }

    private val sys = "you are an engine"
    private fun gw(chunks: Flow<LLMStreamChunk> = flowOf()) =
        ProviderModelGateway(FakeProvider(chunks), systemPrompt = sys)

    @Test
    fun `turn image attachments ride the constructor seam to the provider`() = runTest {
        // [T-m12-image-parts] runEngineTurn never wired this seam — a turn
        // with photos rode the engine path with only the text placeholder
        // while the legacy path sent the pixels. The gateway must forward
        // turn-scoped attachments to streamMessage.
        val fake = FakeProvider(flowOf(LLMStreamChunk.Finished(null)))
        val img = LLMMessage.ImagePart(
            data = ByteArray(3) { 1 },
            mimeType = "image/png",
        )
        val g = ProviderModelGateway(
            fake,
            systemPrompt = sys,
            imageParts = listOf(img),
        )
        g.stream(
            listOf(EngineMessage(role = EngineRole.USER, text = "смотри фото")),
            tools = emptyList(),
            maxTokens = 8,
        ).toList()
        assertEquals(1, fake.lastImageParts.size)
        // And the user text still rides the message list itself.
        assertEquals("смотри фото", fake.seenMessages.last().content)
    }

    @Test
    fun `chunk mapping covers the engine contract`() = runTest {
        val g = gw(
            flowOf(
                LLMStreamChunk.Started,
                LLMStreamChunk.Text("hello "),
                LLMStreamChunk.ThinkingDelta("thinking..."),
                LLMStreamChunk.ToolUseStart("c1", "file_read"),
                LLMStreamChunk.ToolInputDelta("c1", "{\"pa"),
                LLMStreamChunk.ToolCallComplete(
                    "c1", "file_read", JSONObject(mapOf("path" to "/a.kt")),
                ),
                LLMStreamChunk.Usage(LLMUsage(inputTokens = 10, outputTokens = 5)),
                LLMStreamChunk.Finished(stopReason = "tool_calls"),
            ),
        )
        val events = g.stream(emptyList(), emptyList(), 100).toList()
        // dropped: Started (no engine event); progressive surfaces pass 1:1
        assertEquals(
            listOf(
                StreamEvent.TextDelta("hello "),
                StreamEvent.ReasoningDelta("thinking..."),
                StreamEvent.ToolUseStarted("c1", "file_read"),
                StreamEvent.ToolInputDelta("c1", "{\"pa"),
                StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                StreamEvent.Usage(10, 5),
                StreamEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `thrown stream failure becomes a recoverable Failure event`() = runTest {
        val g = gw(
            flow {
                emit(LLMStreamChunk.Text("partial"))
                throw java.io.IOException("connection closed")
            },
        )
        val events = g.stream(emptyList(), emptyList(), 100).toList()
        assertEquals(
            StreamEvent.Failure("connection closed", recoverable = true),
            events.last(),
        )
    }

    @Test
    fun `engine history maps to the production message shape`() = runTest {
        val fake = FakeProvider(flowOf(LLMStreamChunk.Finished(null)))
        val g = ProviderModelGateway(fake, systemPrompt = sys)
        g.stream(
            listOf(
                EngineMessage(EngineRole.USER, text = "hi"),
                EngineMessage(
                    EngineRole.ASSISTANT,
                    text = "let me read",
                    toolCalls = listOf(
                        EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}"),
                    ),
                ),
                EngineMessage(
                    EngineRole.TOOL,
                    text = "file body",
                    toolCallId = "c1",
                    toolName = "file_read",
                ),
            ),
            emptyList(),
            50,
        ).toList()

        val seen = fake.seenMessages
        assertEquals(3, seen.size)
        assertEquals(LLMMessage.Role.USER, seen[0].role)
        assertEquals("hi", seen[0].content)
        // assistant: tool use parts, exactly like the ViewModel loop persists
        assertEquals(LLMMessage.Role.ASSISTANT, seen[1].role)
        assertEquals("let me read", seen[1].content)
        val toolUse = seen[1].contentParts.filterIsInstance<AgentContentPart.ToolUse>().single()
        assertEquals("c1", toolUse.id)
        assertEquals("file_read", toolUse.name)
        assertEquals("/a.kt", toolUse.input.optString("path"))
        // tool result: USER message + ToolResult part (the provider layer's
        // own wire mapping — OpenAI role:"tool", Anthropic tool_result)
        assertEquals(LLMMessage.Role.USER, seen[2].role)
        val tr = seen[2].contentParts.filterIsInstance<AgentContentPart.ToolResult>().single()
        assertEquals("c1", tr.id)
        assertEquals("file_read", tr.name)
        assertEquals("file body", tr.content)
    }

    @Test
    fun `malformed tool args map to an empty object not a crash`() = runTest {
        val fake = FakeProvider(flowOf(LLMStreamChunk.Finished(null)))
        val g = ProviderModelGateway(fake)
        g.stream(
            listOf(
                EngineMessage(
                    EngineRole.ASSISTANT,
                    toolCalls = listOf(EngineToolCall("c1", "shell_execute", "not json")),
                ),
            ),
            emptyList(),
            50,
        ).toList()
        val toolUse = fake.seenMessages[0].contentParts
            .filterIsInstance<AgentContentPart.ToolUse>().single()
        assertEquals(0, toolUse.input.length())
    }

    @Test
    fun `turn image attachments ride the constructor seam to the wire`() {
        // the turn's user attachments must reach the provider call —
        // without this seam the engine swap would silently drop photos
        val fake = FakeProvider()
        val g = ProviderModelGateway(
            provider = fake,
            systemPrompt = "sys",
            imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2), "image/png")),
        )
        kotlinx.coroutines.test.runTest {
            g.stream(
                listOf(EngineMessage(EngineRole.USER, text = "look")),
                emptyList(),
                128,
            ).collect { }
        }
        org.junit.Assert.assertEquals(1, fake.lastImageParts.size)
        org.junit.Assert.assertEquals("image/png", fake.lastImageParts[0].mimeType)
        org.junit.Assert.assertEquals("sys", fake.lastSystemPrompt)
    }

    @Test
    fun `assistant reasoningContent passes to the provider message`() {
        val g = gw()
        val m = g.toLLMMessage(
            EngineMessage(
                role = EngineRole.ASSISTANT,
                text = "answer",
                reasoningContent = "thought chain",
            ),
        )
        org.junit.Assert.assertEquals("thought chain", m.reasoningContent)
    }

    @Test
    fun `TOOL message without toolCallId fails fast at the boundary`() {
        val g = gw()
        val err = runCatching {
            g.toLLMMessage(EngineMessage(EngineRole.TOOL, text = "body"))
        }.exceptionOrNull()
        org.junit.Assert.assertNotNull(err)
        org.junit.Assert.assertTrue(
            err!!.message!!.contains("toolCallId"),
        )
    }

    @Test
    fun `SYSTEM message is rejected not masqueraded as user`() {
        val g = gw()
        val err = runCatching {
            g.toLLMMessage(EngineMessage(EngineRole.SYSTEM, text = "secret system"))
        }.exceptionOrNull()
        org.junit.Assert.assertNotNull(err)
        org.junit.Assert.assertTrue(
            err!!.message!!.contains("system prompt"),
        )
    }

    @Test
    fun `end to end loop over the production adapter`() = runTest {
        // The full M8 seam: EngineAgentLoop + ProviderModelGateway + a
        // scripted provider — one tool round then stop.
        val fake = FakeProvider(
            flowOf(
                LLMStreamChunk.ToolCallComplete(
                    "c1", "file_read", JSONObject(mapOf("path" to "/a.kt")),
                ),
                LLMStreamChunk.Finished("tool_calls"),
            ),
            flowOf(LLMStreamChunk.Finished(null)),
        )
        val registry = ToolRegistry().apply {
            register(
                object : EngineTool {
                    override val name = "file_read"
                    override val mutation = MutationKind.READ
                    override fun definition() = AgentToolDefinition(
                        name = "file_read",
                        description = "Read",
                        parameters = mapOf("path" to AgentToolParam(type = "string", description = "")),
                        required = listOf("path"),
                    )

                    override suspend fun execute(argsJson: String, ctx: ToolContext) =
                        com.openminis.app.tools.ToolExecutionResult("ok", true)
                },
            )
        }
        val loop = EngineAgentLoop(ProviderModelGateway(fake), registry) { _, _ ->
            EngineAgentLoop.ToolOutcome("body", true)
        }
        val events = loop.runTurn(
            TurnInput("s1", "read it", emptyList(), PermissionMode.AUTO),
        ).toList()
        assertTrue(events.last() is AgentEvent.TurnFinished)
        assertEquals("stop", (events.last() as AgentEvent.TurnFinished).reason)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
    }
}
