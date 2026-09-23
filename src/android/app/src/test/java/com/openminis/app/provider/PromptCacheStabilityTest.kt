package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [T-prompt-cache-stability] THE prompt-cache contract, pinned by measuring
 * REAL request bodies: OpenAI/DeepSeek/GLM prefix caching requires the
 * messages array of iteration N+1 to be BYTE-IDENTICAL to iteration N's,
 * plus appended tail messages — anything else re-ingests the whole uncached
 * prefix on every tool round-trip (measured 24.7s/call at 137K prompt
 * tokens with only 14% cache hit on 23.09.2026).
 *
 * This test replays the agent-loop shape: request 1 = a tool-call round;
 * request 2 = the SAME history plus the completed assistant tool_calls
 * message and its tool result. If any step of our serialization mutates
 * history bytes between requests, the byte-diff catches the exact message
 * index — and CI fails before the regression ever reaches a phone.
 */
class PromptCacheStabilityTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun okResponse(): MockResponse = MockResponse().setBody(
        """
        {
            "choices": [{
                "message": {"role": "assistant", "content": "ok"},
                "finish_reason": "stop"
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5}
        }
        """.trimIndent(),
    )

    private fun baseHistory(): List<LLMMessage> = listOf(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "прочитай файл и исправь баг",
            contentParts = listOf(
                AgentContentPart.Text("прочитай файл и исправь баг"),
            ),
        ),
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(
                AgentContentPart.Text("Читаю файл."),
                AgentContentPart.ToolUse(
                    id = "call_1",
                    name = "file_read",
                    input = JSONObject("""{"path":"/var/minis/workspace/foo.kt","lines":100}"""),
                ),
            ),
            reasoningContent = "нужно сначала прочитать",
        ),
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_1",
                    name = "file_read",
                    content = "fun main() { println(1) }",
                ),
            ),
        ),
    )

    private val tools = listOf(
        AgentToolDefinition(
            name = "file_read",
            description = "Read a file from the sandbox",
            parameters = mapOf("path" to AgentToolParam("string", "Absolute path")),
            required = listOf("path"),
        ),
    )

    @Test
    fun `messages prefix is byte-stable across agent-loop iterations`() = runBlocking {
        val system = "You are a careful engineer. Ответь по-русски. Тулзы доступны."

        // ── Iteration 1: the first tool round.
        server.enqueue(okResponse())
        provider.sendMessage(baseHistory(), system, 2048, tools = tools)

        // ── Iteration 2: the loop appended the assistant's second tool call
        // and its result — everything BEFORE it must be byte-identical.
        val appended = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.Text("Правлю."),
                    AgentContentPart.ToolUse(
                        id = "call_2",
                        name = "file_edit",
                        input = JSONObject("""{"path":"/var/minis/workspace/foo.kt","old":"1","new":"2"}"""),
                    ),
                ),
                reasoningContent = "заменю 1 на 2",
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "call_2",
                        name = "file_edit",
                        content = "OK",
                    ),
                ),
            ),
        )
        server.enqueue(okResponse())
        provider.sendMessage(baseHistory() + appended, system, 2048, tools = tools)

        // ── The verdict: real request bodies, compared as serialized JSON.
        val body1 = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        val body2 = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        val msgs1 = JSONObject(body1).getJSONArray("messages")
        val msgs2 = JSONObject(body2).getJSONArray("messages")

        assertEquals(
            "iteration 2 must append exactly the assistant tool-call turn + its result",
            msgs1.length(),
            msgs2.length() - appended.size,
        )
        for (i in 0 until msgs1.length()) {
            assertEquals(
                "messages[$i] mutated between iterations — this byte diff is what " +
                    "destroys the provider prompt-cache prefix (every tool round-trip " +
                    "then re-ingests the full uncached prompt)",
                msgs1[i].toString(),
                msgs2[i].toString(),
            )
        }
        // The cached prefix also covers tools: its serialization must be
        // byte-stable between requests as well.
        assertEquals(
            "tools array mutated between iterations",
            JSONObject(body1).getJSONArray("tools").toString(),
            JSONObject(body2).getJSONArray("tools").toString(),
        )
        assertTrue(msgs1.length() > 0)
    }
}
