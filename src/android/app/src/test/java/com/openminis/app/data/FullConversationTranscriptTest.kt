package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-external] The export file must carry the COMPLETE session —
 * these tests pin the full-fidelity contract of FullConversationTranscript
 * against every AgentContentPart kind and the turn-numbering rules.
 */
class FullConversationTranscriptTest {

    private fun user(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
    )

    private fun assistant(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = parts.toList(),
    )

    private fun userResults(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = parts.toList(),
    )

    @Test
    fun `tool_use input is rendered verbatim`() {
        val input = JSONObject().put("cmd", "grep -rn leaked /var").put("n", 42)
        val out = FullConversationTranscript.render(
            listOf(user("ищи"), assistant(AgentContentPart.ToolUse("c1", "shell", input))),
        )
        assertTrue(out.contains("[tool:shell]: ${input.toString()}"))
    }

    @Test
    fun `tool_result body is rendered verbatim, not capped`() {
        val longBody = "x".repeat(50_000)
        val out = FullConversationTranscript.render(
            listOf(
                user("ищи"),
                assistant(AgentContentPart.ToolUse("c1", "shell", JSONObject())),
                userResults(AgentContentPart.ToolResult("c1", "shell", longBody)),
            ),
        )
        // The single most important guarantee of the export: NO body caps —
        // the internal collector would have shown 600 chars + "(old…omitted)".
        assertTrue(out.contains(longBody))
    }

    @Test
    fun `image renders as an informative stub`() {
        val out = FullConversationTranscript.render(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "вот",
                    contentParts = listOf(
                        AgentContentPart.ImageData(ByteArray(1024), "image/png"),
                    ),
                ),
            ),
        )
        assertTrue(out.contains("[image: image/png, 1024 bytes"))
        // ...but the stub must not pretend to carry the bytes.
        assertFalse(out.contains("iVBOR"))
    }

    @Test
    fun `turn numbers count only real user turns, not tool-result rows`() {
        val out = FullConversationTranscript.render(
            listOf(
                user("первый вопрос"),
                assistant(AgentContentPart.ToolUse("c1", "shell", JSONObject())),
                userResults(AgentContentPart.ToolResult("c1", "shell", "ok")),
                user("второй вопрос"),
            ),
        )
        assertTrue(out.contains("### Turn 1"))
        assertTrue(out.contains("### Turn 2"))
        assertFalse(out.contains("### Turn 3"))
    }

    @Test
    fun `empty history renders empty, contentless user row is not a turn`() {
        assertEquals("", FullConversationTranscript.render(emptyList()))
        // A user message with neither text nor parts carries no content — it
        // must NOT open a numbered turn (turn numbering is for real user
        // turns; a blank row is transcript noise).
        val out = FullConversationTranscript.render(listOf(user("")))
        assertFalse(out.contains("### Turn 1"))
        // ...but a user row whose text lives in a PART does count.
        val withPart = FullConversationTranscript.render(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(AgentContentPart.Text("вопрос в части")),
                ),
            ),
        )
        assertTrue(withPart.contains("### Turn 1"))
        assertTrue(withPart.contains("вопрос в части"))
    }
}
