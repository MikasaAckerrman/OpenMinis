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
    fun `warm tier keeps the head, cold tier stubs`() {
        // [T-aggressive-elision] Three-tier elision: fresh (3 user turns)
        // verbatim; warm (next 3 user turns) head-trimmed to 400 chars;
        // everything older stubbed. The old flat 6-turn protection carried
        // hundreds of KB of dead scratchpad (telemetry: 182K-token bodies).
        val msgs = listOf(
            assistant(big()), user(),            // idx 0,1   — cold
            assistant(big()), user(),            // idx 2,3   — cold
            assistant(big()), user(),            // idx 4,5   — warm
            assistant(big()), user(),            // idx 6,7   — warm
            assistant(big()), user(),            // idx 8,9   — warm
            assistant(big()), user(),            // idx 10,11 — fresh
            assistant(big()), user(),            // idx 12,13 — fresh
            assistant(big()), user(),            // idx 14,15 — fresh
        )
        val r = ReasoningElider.elide(msgs) // defaults: 3 fresh + 3 warm
        // Cold: stub.
        assertTrue(r.messages[0].reasoningContent == "[reasoning elided]")
        assertTrue(r.messages[2].reasoningContent == "[reasoning elided]")
        // Warm: head + ellipsis marker, still present (DeepSeek contract),
        // and materially shorter than the 2000-char original.
        val warm = r.messages[6].reasoningContent!!
        assertTrue(warm.length == ReasoningElider.WARM_HEAD_CHARS + "…[elided]".length)
        assertTrue(warm.startsWith("R".repeat(10)))
        // Fresh: verbatim.
        assertTrue(r.messages[12].reasoningContent == big())
        assertEquals(msgs.size, r.messages.size)
        assertTrue(r.charsSaved > 0)
    }

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
        // [user, asst, user, asst] with protect=2: walking back from the
        // end, the 2nd protected user turn is the FIRST element →
        // protectedFromIdx=0 → the whole list is the protected tail.
        val msgs = listOf(user(), assistant(big()), user(), assistant(big()))
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 2)
        assertEquals(0, r.elidedCount)
        assertSame(msgs, r.messages)
    }

    @Test
    fun `assistant before the only protected user turn IS elided`() {
        // [asst, user]: the single user turn is protected, but the older
        // assistant head is prunable — same walk semantics as
        // PostAnchorPrune ("everything before the first of the last N
        // user-text turns").
        val msgs = listOf(assistant(big()), user())
        val r = ReasoningElider.elide(msgs, protectRecentUserTextTurns = 2)
        assertEquals(1, r.elidedCount)
        assertEquals("[reasoning elided]", r.messages[0].reasoningContent)
    }
}
