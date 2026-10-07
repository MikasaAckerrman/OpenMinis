package com.openminis.app.engine

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engine-user-text-annihilation] Regression suite for the 07.10 vc100
 * incident: ProviderModelGateway.fromLLMMessage dropped USER text parts —
 * every message the send path produces carries contentParts, so the engine
 * request carried ZERO user messages and the model pattern-continued the
 * last assistant turn ("agent doesn't see my messages"). The diet sent 116
 * messages, the engine kept 105: the 11 missing were exactly the user
 * turns.
 *
 * The send-path shape (send() → agentHistory.add) is:
 *   LLMMessage(role=USER, content=trimmed, contentParts=[Text(trimmed), …])
 * — these tests freeze that contract end to end, including the
 * [Engine]-path guard precondition: a converted history ALWAYS contains at
 * least one USER message whenever the source did.
 */
class EngineUserTextPreservationTest {

    private fun convert(msg: LLMMessage) =
        ProviderModelGateway.fromLLMMessage(msg)

    @Test
    fun `send-path user message keeps its text as a USER engine message`() {
        // Exact shape send() builds: contentParts=[Text(trimmed)].
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "ну я установил",
            contentParts = listOf(AgentContentPart.Text("ну я установил")),
        )
        val out = convert(msg)
        assertEquals(1, out.size)
        assertEquals(EngineRole.USER, out[0].role)
        assertEquals("ну я установил", out[0].text)
        assertTrue(out[0].toolCalls.isEmpty())
        assertNull(out[0].toolCallId)
    }

    @Test
    fun `multi-part user message text parts are joined`() {
        // Attachments: send() plants "[attached image: path]" text parts
        // alongside ImageData parts.
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "смотри скрин",
            contentParts = listOf(
                AgentContentPart.Text("смотри скрин"),
                AgentContentPart.Text("[attached image: /tmp/a.png]"),
                AgentContentPart.ImageData(ByteArray(4), "image/png"),
            ),
        )
        val out = convert(msg)
        assertEquals(1, out.size)
        assertEquals(EngineRole.USER, out[0].role)
        assertTrue(out[0].text.contains("смотри скрин"))
        assertTrue(out[0].text.contains("[attached image: /tmp/a.png]"))
    }

    @Test
    fun `user message with text and tool result emits USER then TOOL in order`() {
        // Anthropic ordering: text block first, then tool_result blocks.
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "text + result",
            contentParts = listOf(
                AgentContentPart.Text("text + result"),
                AgentContentPart.ToolResult(
                    id = "call_1",
                    name = "shell_execute",
                    content = "done",
                ),
            ),
        )
        val out = convert(msg)
        assertEquals(2, out.size)
        assertEquals(EngineRole.USER, out[0].role)
        assertEquals("text + result", out[0].text)
        assertEquals(EngineRole.TOOL, out[1].role)
        assertEquals("call_1", out[1].toolCallId)
        assertEquals("shell_execute", out[1].toolName)
    }

    @Test
    fun `pure tool-result user message emits TOOL only — no empty USER`() {
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "call_2",
                    name = "file_read",
                    content = "payload",
                ),
            ),
        )
        val out = convert(msg)
        assertEquals(1, out.size)
        assertEquals(EngineRole.TOOL, out[0].role)
        assertEquals("call_2", out[0].toolCallId)
    }

    @Test
    fun `whitespace-only user text is not emitted as a message`() {
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "   ",
            contentParts = listOf(AgentContentPart.Text("   ")),
        )
        val out = convert(msg)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `assistant tool-use turn is unchanged by the fix`() {
        val msg = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "running it",
            contentParts = listOf(
                AgentContentPart.Text("running it"),
                AgentContentPart.ToolUse(
                    id = "call_3",
                    name = "grep",
                    input = JSONObject("""{"q":"x"}"""),
                ),
            ),
        )
        val out = convert(msg)
        assertEquals(1, out.size)
        assertEquals(EngineRole.ASSISTANT, out[0].role)
        assertEquals("running it", out[0].text)
        assertEquals(1, out[0].toolCalls.size)
        assertEquals("call_3", out[0].toolCalls[0].id)
    }

    @Test
    fun `plain user message without contentParts still maps directly`() {
        // Legacy branch (parts == null): pre-existing behaviour, frozen.
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "plain",
        )
        val out = convert(msg)
        assertEquals(1, out.size)
        assertEquals(EngineRole.USER, out[0].role)
        assertEquals("plain", out[0].text)
    }

    @Test
    fun `realistic send-path slice converts with user text present`() {
        // The guard precondition end-to-end: a history slice shaped like
        // the real outbound diet (assistant toolUse → user toolResult →
        // user text turn) must yield a USER message AFTER conversion.
        val slice = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "call_9",
                        name = "shell_execute",
                        input = JSONObject("""{"command":"ls"}"""),
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "call_9",
                        name = "shell_execute",
                        content = "file1\nfile2",
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "ну я установил",
                contentParts = listOf(AgentContentPart.Text("ну я установил")),
            ),
        )
        val engineHistory = slice.flatMap { ProviderModelGateway.fromLLMMessage(it) }
        val userMessages = engineHistory.filter { it.role == EngineRole.USER }
        assertTrue(
            "converted history must carry the user's text",
            userMessages.isNotEmpty(),
        )
        assertEquals("ну я установил", userMessages.last().text)
        // The last message of the request must BE the user's turn — the
        // model answers what the user just said, not the tool echo.
        assertEquals(EngineRole.USER, engineHistory.last().role)
    }
}
