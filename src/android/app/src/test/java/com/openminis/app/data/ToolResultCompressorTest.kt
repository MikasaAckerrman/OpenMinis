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

    /**
     * A REAL tool round: tool_result part + the text part the runtime
     * attaches (system reminders etc). Only rounds carrying text count
     * as "user-text turns" in the shared protected-tail walk (same
     * convention as PostAnchorPrune / ReasoningElider) — bare tool-only
     * rounds are compressible history. The tests encode THAT contract.
     */
    private fun realRound(id: String, content: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.Text(text = "[round $id]"),
            toolResult(id, content),
        ),
    )

    private fun assistant(text: String = "ok") = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
    )

    private fun big() = "O".repeat(3000)

    @Test
    fun `old tool result is head-trimmed, protected tail verbatim`() {
        // A bare tool-only round (no text part — e.g. a legacy sync) counts
        // as OLD history; the following real rounds (text + tool_result)
        // are the protected 3-turn tail.
        val msgs = listOf(
            userTurn(toolResult("t0", big())),      // old: no text part
            assistant(),
            realRound("t1", big()),
            assistant(),
            realRound("t2", big()),
            assistant(),
            realRound("t3", big()),
        )
        val r = ToolResultCompressor.compress(msgs)
        // [T-current-turn-tools-only] Previous turns' tool rounds are now
        // head-trimmed too (the on-device 1.26 MB body regression): the
        // model already answered on them, heads keep continuity.
        assertEquals(3, r.compressedCount)
        assertTrue(r.charsSaved > 0)
        val oldPart = r.messages[0].contentParts[0] as AgentContentPart.ToolResult
        // Head + explicit "+N chars compressed" marker.
        assertTrue(oldPart.content.startsWith("O".repeat(ToolResultCompressor.DEFAULT_HEAD_CHARS)))
        assertTrue(oldPart.content.contains("+${big().length - ToolResultCompressor.DEFAULT_HEAD_CHARS} chars compressed"))
        // Protected: the tool_result of the CURRENT (last) round stays verbatim.
        val kept = r.messages[6].contentParts
            .filterIsInstance<AgentContentPart.ToolResult>()
            .first()
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
        // A single REAL round (text + tool result) = the last user-text
        // turn → everything after it is protected; the assistant head has
        // no parts → nothing to compress, identity result.
        val msgs = listOf(assistant(), realRound("t1", big()))
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
