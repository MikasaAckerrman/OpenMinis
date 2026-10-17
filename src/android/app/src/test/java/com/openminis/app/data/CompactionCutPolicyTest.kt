package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-compact-incremental] The secondary cut boundary: a marathon stretch of
 * tool turns used to have NO safe cut inside the 25k budget, so a retry
 * degraded to the full span (40+ windows at once). A completed tool-result
 * run is a safe cut — the fix.
 */
class CompactionCutPolicyTest {

    private fun userText(vararg t: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = t.joinToString(" "),
    )

    private fun userResults(n: Int) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = List(n) {
            AgentContentPart.ToolResult(
                id = "c$it",
                name = "shell",
                content = "ok",
            )
        },
    )

    private fun assistant() = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "thinking",
    )

    @Test
    fun `plain user text is the primary boundary`() {
        val h = listOf(assistant(), userText("hi"))
        assertTrue(CompactionCutPolicy.isSafeBoundary(h, 1))
        assertFalse(CompactionCutPolicy.isSafeBoundary(h, 0))
    }

    @Test
    fun `completed result run is a safe secondary boundary`() {
        val h = listOf(assistant(), userResults(3), assistant())
        assertTrue(CompactionCutPolicy.isSafeBoundary(h, 1))
    }

    @Test
    fun `result run followed by more results is NOT safe - batch continues`() {
        val h = listOf(assistant(), userResults(2), userResults(2), assistant())
        assertFalse(CompactionCutPolicy.isSafeBoundary(h, 1))
        assertTrue(CompactionCutPolicy.isSafeBoundary(h, 2))
    }

    @Test
    fun `message with tool_use is never a boundary`() {
        val h = listOf(
            assistant(),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c0", "shell", "ok"),
                    AgentContentPart.ToolUse("c1", "shell", org.json.JSONObject()),
                ),
            ),
        )
        assertFalse(CompactionCutPolicy.isSafeBoundary(h, 1))
    }

    @Test
    fun `out of range and empty parts are handled`() {
        val h = listOf(userText("hi"))
        assertFalse(CompactionCutPolicy.isSafeBoundary(h, 5))
        assertFalse(CompactionCutPolicy.isSafeBoundary(h, -1))
        // user message with empty parts list behaves as plain text
        val empty = LLMMessage(role = LLMMessage.Role.USER, content = "x", contentParts = emptyList())
        assertTrue(CompactionCutPolicy.isSafeBoundary(listOf(empty), 0))
    }
}
