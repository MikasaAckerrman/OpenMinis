package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-microcompact] Contract tests: protocol safety (pairing and ids
 * intact), recency protection, size gates, error preservation.
 */
class ToolResultMicrocompactTest {

    private fun msg(role: LLMMessage.Role, parts: List<AgentContentPart>) = LLMMessage(
        role = role,
        content = "",
        contentParts = parts,
    )

    private fun fatResult(id: String, chars: Int, isError: Boolean = false) =
        AgentContentPart.ToolResult(
            id = id,
            name = "shell",
            content = "x".repeat(chars),
            isError = isError,
        )

    @Test
    fun `short history is returned untouched`() {
        val h = (1..4).map { msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("t$it", 5_000))) }
        assertTrue(ToolResultMicrocompact.apply(h) === h)
    }

    @Test
    fun `old fat tool result bodies are truncated, ids and pairing survive`() {
        val fat = fatResult("t1", 10_000)
        val old = msg(LLMMessage.Role.ASSISTANT, listOf(AgentContentPart.ToolUse("t1", "shell", JSONObject()), fat))
        val h = listOf(old) + (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 50)))
        }
        val out = ToolResultMicrocompact.apply(h)
        val result = (out.first().contentParts!![1] as AgentContentPart.ToolResult)
        assertEquals("t1", result.id)
        assertEquals("shell", result.name)
        // Head, elision marker, and tail are all present.
        assertTrue(result.content.startsWith("x".repeat(ToolResultMicrocompact.KEEP_HEAD_CHARS)))
        assertTrue(result.content.contains("[… tool output microcompacted: 10000 chars total"))
        assertTrue(result.content.endsWith("x".repeat(ToolResultMicrocompact.KEEP_TAIL_CHARS)))
        // The tool_use answer pairing is untouched.
        assertEquals("t1", (out.first().contentParts!![0] as AgentContentPart.ToolUse).id)
    }

    @Test
    fun `recent entries are never truncated`() {
        val recent = (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 9_000)))
        }
        val old = msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("t1", 9_000)))
        val out = ToolResultMicrocompact.apply(listOf(old) + recent)
        // Every result but the first (old) keeps full length.
        out.drop(1).forEach { m ->
            val r = m.contentParts!![0] as AgentContentPart.ToolResult
            assertEquals(9_000, r.content.length)
        }
    }

    @Test
    fun `small old results are left alone`() {
        val old = msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("t1", 800)))
        val h = listOf(old) + (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 50)))
        }
        val out = ToolResultMicrocompact.apply(h)
        assertEquals(800, (out.first().contentParts!![0] as AgentContentPart.ToolResult).content.length)
    }

    @Test
    fun `errors are preserved verbatim regardless of size`() {
        val err = AgentContentPart.ToolResult(id = "e1", name = "shell", content = "E".repeat(9_000), isError = true)
        val old = msg(LLMMessage.Role.ASSISTANT, listOf(err))
        val h = listOf(old) + (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 50)))
        }
        val out = ToolResultMicrocompact.apply(h)
        val r = (out.first().contentParts!![0] as AgentContentPart.ToolResult)
        assertEquals(9_000, r.content.length)
        assertTrue(r.isError)
    }

    @Test
    fun `original list is not mutated`() {
        val old = msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("t1", 9_000)))
        val h = listOf(old) + (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 50)))
        }
        ToolResultMicrocompact.apply(h)
        assertEquals(9_000, (h[0].contentParts!![0] as AgentContentPart.ToolResult).content.length)
    }

    @Test
    fun `wire size actually shrinks`() {
        val old = (1..6).map { msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("t$it", 50_000))) }
        val recent = (1..ToolResultMicrocompact.KEEP_RECENT_ENTRIES).map {
            msg(LLMMessage.Role.ASSISTANT, listOf(fatResult("r$it", 50)))
        }
        val h = old + recent
        val out = ToolResultMicrocompact.apply(h)
        fun wireBytes(list: List<LLMMessage>) = list.sumOf { m ->
            m.contentParts.orEmpty().sumOf { p -> (p as? AgentContentPart.ToolResult)?.content?.length ?: 0 }
        }
        assertTrue(wireBytes(out) < wireBytes(h) / 5)
        assertFalse(out === h)
    }
}
