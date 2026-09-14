package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the safe N-way split that replaced the blind halve.
 *
 * Live failure 2026-09-14: splitting at messages.size/2 cut mid-tool_call,
 * leaving the next chunk opening on an orphan tool_result the summarizer
 * couldn't make sense of. The summaries came out "twitchy" when re-glued
 * for the merge. These tests pin down the rules so this never returns:
 *   - isRealUserTurn excludes USER messages whose parts are ALL ToolResult
 *     (those are tool-response wrappers, not fresh user instructions).
 *   - findSafeSplitPoints only returns indices of real user turns.
 *   - splitAtSafeUserTurns rejects invalid splits (chunk count > safe-points)
 *     rather than cutting mid-tool_pair.
 *
 * The split logic is private; we exercise it through reflective access is
 * not used here — the live wrapper (generateCompactSummaryWithSplitting) is
 * tested separately via the broader ChatViewModelTest under the
 * integration test suite. These unit tests cover the pure-logic helpers.
 */
class ChatViewModelSafeSplitTest {

    /** Tiny helper — build a USER message with optional content parts. */
    private fun userMsg(
        content: String = "",
        parts: List<AgentContentPart> = emptyList(),
    ): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        contentParts = parts,
    )

    private fun assistantMsg(
        content: String,
        toolUse: AgentContentPart.ToolUse? = null,
    ): LLMMessage = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = content,
        contentParts = listOfNotNull(AgentContentPart.Text(content), toolUse),
    )

    private fun toolResultMsg(
        id: String = "tu_1",
        content: String = "result",
    ): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        contentParts = listOf(
            AgentContentPart.ToolResult(
                id = id, name = "test", content = content,
            ),
        ),
    )

    /**
     * Sanity: a USER message whose parts are 100% ToolResult is NOT a
     * real user turn, even when its `content` field has text (the text is
     * rendered from the tool output, not a fresh user instruction).
     */
    @Test
    fun `tool-result wrapper is not a real user turn`() {
        val wrapper = toolResultMsg(content = "result text here")
        assertEquals("a tool-result-only USER message must not be a real user turn", false, isRealUserTurn(wrapper))
    }

    /** Real user turn: text content present. */
    @Test
    fun `user message with text content is a real user turn`() {
        val u = userMsg(content = "Привет, расскажи про X", parts = emptyList())
        assertTrue(isRealUserTurn(u))
    }

    /** Real user turn: text content part present alongside a tool result. */
    @Test
    fun `user message with mixed text and tool result parts is a real user turn`() {
        val u = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "user's actual question",
            contentParts = listOf(
                AgentContentPart.Text("user's actual question"),
                AgentContentPart.ToolResult(id = "tu_1", name = "test", content = "x"),
            ),
        )
        assertTrue(isRealUserTurn(u))
    }

    /** Non-USER messages are never real user turns. */
    @Test
    fun `assistant message is never a real user turn`() {
        val a = assistantMsg("Hello, here is my answer")
        assertFalse(isRealUserTurn(a))
    }

    /**
     * No tool-result wrappers → cut points exist at every real user turn
     * start. Sequence: real-user, assistant, real-user, assistant, ...
     * Safe points: 0, 2, 4, 6, 8.
     */
    @Test
    fun `findSafeSplitPoints with alternating user-assistant pairs`() {
        val msgs = listOf(
            userMsg("Q1"),
            assistantMsg("A1"),
            userMsg("Q2"),
            assistantMsg("A2"),
            userMsg("Q3"),
            assistantMsg("A3"),
            userMsg("Q4"),
            assistantMsg("A4"),
            userMsg("Q5"),
            assistantMsg("A5"),
        )
        assertEquals(listOf(0, 2, 4, 6, 8), findSafeSplitPoints(msgs))
    }

    /**
     * The dangerous case the user reported: a USER message containing ONLY
     * a ToolResult (no fresh text) must be SKIPPED by findSafeSplitPoints
     * so that splitting there does not orphan a tool_call/result pair.
     */
    @Test
    fun `findSafeSplitPoints skips tool-result wrappers`() {
        val msgs = listOf(
            userMsg("Q1"),                         // 0 - safe
            assistantMsg("A1"),                    // 1
            userMsg("Q2"),                         // 2 - safe
            assistantMsg("A2-read", toolUse = AgentContentPart.ToolUse(
                id = "tu_1", name = "read_file", input = JSONObject().put("path", "/x"),
            )),                                    // 3 - has ToolUse
            toolResultMsg(id = "tu_1", content = "result text"),  // 4 - SKIP (tool-result only)
            assistantMsg("A2-final"),              // 5
            userMsg("Q3"),                         // 6 - safe
            assistantMsg("A3"),                    // 7
        )
        val points = findSafeSplitPoints(msgs)
        // 0, 2, 6. NOT 4 (the tool-result wrapper).
        assertEquals(listOf(0, 2, 6), points)
    }

    /**
     * splitAtSafeUserTurns: when a chunk would end mid-tool_use (leaving
     * the next chunk to start with the orphan tool_result), the splitter
     * must pick a LATER safe point so the boundary lands on a real user
     * turn instead. Concretely: trying to split into 3 chunks when there
     * are 3 safe points must never produce a chunk ending on tool_use.
     */
    @Test
    fun `splitAtSafeUserTurns never cuts inside a tool_use tool_result pair`() {
        val msgs = listOf(
            userMsg("Q1"),                         // 0
            assistantMsg("A1"),                    // 1
            userMsg("Q2"),                         // 2 - safe
            assistantMsg("A2-read", toolUse = AgentContentPart.ToolUse(
                id = "tu_1", name = "read", input = JSONObject(),
            )),                                    // 3 - ToolUse
            toolResultMsg(id = "tu_1"),            // 4 - tool-result wrapper
            assistantMsg("A2-final"),              // 5
            userMsg("Q3"),                         // 6 - safe
            assistantMsg("A3"),                    // 7
        )
        val chunks = splitAtSafeUserTurns(msgs, n = 3)
        assertTrue("expected at least 2 chunks, got ${chunks.size}", chunks.size >= 2)
        for ((i, c) in chunks.withIndex()) {
            val last = c.last()
            val first = c.first()
            // Each chunk must start on a real user turn (not a tool-result wrapper).
            assertTrue(
                "chunk $i must start on a real user turn (got $first)",
                isRealUserTurn(first) || i == 0,
            )
            // And must not end on a tool_use (would orphan the call).
            val lastHasToolUse = last.contentParts.any { it is AgentContentPart.ToolUse }
            assertFalse("chunk $i must not end on tool_use; got $last", lastHasToolUse)
        }
    }

    /** Single USER message (one-shot) — no safe split possible, returns original. */
    @Test
    fun `splitAtSafeUserTurns on single element returns unchanged`() {
        val msgs = listOf(userMsg("only"))
        val chunks = splitAtSafeUserTurns(msgs, n = 2)
        assertEquals(1, chunks.size)
        assertEquals(msgs, chunks[0])
    }

    /** if the requested n exceeds the safe-points count, fall back to fewer. */
    @Test
    fun `splitAtSafeUserTurns reduces n when not enough safe points`() {
        // 4 user messages → 4 safe points (0, 2, 4, 6). Asking for n=5
        // should be reduced to n = safePoints.size - 1 = 3.
        val msgs = listOf(
            userMsg("Q1"), assistantMsg("A1"),
            userMsg("Q2"), assistantMsg("A2"),
            userMsg("Q3"), assistantMsg("A3"),
            userMsg("Q4"), assistantMsg("A4"),
        )
        val chunks = splitAtSafeUserTurns(msgs, n = 5)
        assertEquals("n=5 should reduce to 3, got ${chunks.size}", 3, chunks.size)
    }
}
