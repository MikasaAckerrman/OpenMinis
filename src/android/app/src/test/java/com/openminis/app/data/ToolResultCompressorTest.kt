package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultCompressorTest {

    private fun toolResult(id: String, content: String) = AgentContentPart.ToolResult(
        id = id,
        name = "shell_execute",
        content = content,
    )

    private fun userTurn(vararg parts: AgentContentPart) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = parts.toList(),
    )

    private fun assistant(text: String = "ok") = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
    )

    private fun big() = "O".repeat(3000)

    @Test
    fun `old tool result is head-trimmed, protected tail verbatim`() {
        // 4 user turns with tool results; default protect = 3 → the first
        // turn's result is old (compressed), the last 3 stay verbatim.
        val msgs = listOf(
            userTurn(toolResult("t1", big())),
            assistant(), userTurn(toolResult("t2", big())),
            assistant(), userTurn(toolResult("t3", big())),
            assistant(), userTurn(toolResult("t4", big())),
        )
        val r = ToolResultCompressor.compress(msgs)
        assertEquals(1, r.compressedCount)
        assertTrue(r.charsSaved > 0)
        val oldPart = r.messages[0].contentParts[0] as AgentContentPart.ToolResult
        // Head + explicit "+N chars compressed" marker.
        assertTrue(oldPart.content.startsWith("O".repeat(ToolResultCompressor.DEFAULT_HEAD_CHARS)))
        assertTrue(oldPart.content.contains("+${big().length - ToolResultCompressor.DEFAULT_HEAD_CHARS} chars compressed"))
        // Protected: byte-identical objects (no copy).
        assertSame(msgs[1], r.messages[1])
        val kept = r.messages[5].contentParts[0] as AgentContentPart.ToolResult
        assertEquals(big(), kept.content)
        assertEquals(msgs.size, r.messages.size)
    }

    @Test
    fun `short tool result untouched`() {
        val msgs = listOf(
            userTurn(toolResult("t1", "short output")),
            userTurn(toolResult("t2", "short")),
            userTurn(toolResult("t3", "short")),
        )
        val r = ToolResultCompressor.compress(msgs)
        assertEquals(0, r.compressedCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `all protected - identity result`() {
        val msgs = listOf(assistant(), userTurn(toolResult("t1", big())))
        val r = ToolResultCompressor.compress(msgs)
        assertEquals(0, r.compressedCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `empty list`() {
        val r = ToolResultCompressor.compress(emptyList())
        assertEquals(0, r.compressedCount)
        assertSame(emptyList<LLMMessage>(), r.messages)
    }
}
