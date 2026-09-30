package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEliderTest {

    private fun assistant(reasoning: String?, text: String = "ok") = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        reasoningContent = reasoning,
    )

    private fun user(text: String = "hi") = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
    )

    private fun big() = "R".repeat(2000)

    @Test
    fun `old long reasoning is stubbed, recent stays verbatim`() {
        val msgs = listOf(
            assistant(big()), user(), assistant(big()), user(),
            assistant(big()), user(), assistant(big()), user(),
            assistant(big()), user(),
            // protected tail: 3 user turns + everything after the first of them
            assistant(big()), user("last-3"), assistant(big()), user("last-2"),
            assistant(big()), user("last-1"),
        )
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 3)
        assertTrue(r.elidedCount > 0)
        // The three protected assistant blocks stay verbatim.
        val protectedReasoning = r.messages.takeLast(5)
            .filter { it.role == LLMMessage.Role.ASSISTANT }
            .map { it.reasoningContent }
        assertTrue(protectedReasoning.all { it == big() })
        // Old ones are stubs (present, not null — DeepSeek contract).
        assertTrue(r.messages.first().reasoningContent == "[reasoning elided]")
        // Field presence for every assistant turn.
        assertTrue(r.messages.all { it.role != LLMMessage.Role.ASSISTANT || it.reasoningContent != null })
        assertEquals(msgs.size, r.messages.size)
    }

    @Test
    fun `short reasoning untouched`() {
        val msgs = listOf(assistant("short"), user(), assistant("short"), user())
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 1)
        assertEquals(0, r.elidedCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `null reasoning untouched`() {
        val msgs = listOf(assistant(null), user(), assistant(null), user())
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 1)
        assertEquals(0, r.elidedCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `empty list`() {
        val r = ReasoningElider.elide(emptyList())
        assertEquals(0, r.elidedCount)
    }

    @Test
    fun `all protected - nothing elided`() {
        val msgs = listOf(assistant(big()), user())
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 2)
        assertEquals(0, r.elidedCount)
        assertSame(msgs, r.messages)
    }
}
