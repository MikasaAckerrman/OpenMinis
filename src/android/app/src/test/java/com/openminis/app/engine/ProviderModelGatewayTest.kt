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
        private val chunks: Flow<LLMStreamChunk> = flowOf(),
    ) : LLMProvider {
        override val name = "fake"
        override var model = com.openminis.app.data.model.LLMModel(
            id = "fake-model",
            displayName = "Fake",
            provider = "fake",
        )
        var seenMessages: List<LLMMessage> = emptyList()
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
            return chunks
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
        // dropped: Started, ThinkingDelta, ToolUseStart, ToolInputDelta
        assertEquals(
            listOf(
                StreamEvent.TextDelta("hello "),
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
