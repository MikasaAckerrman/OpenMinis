package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTurnCopyTest {

    private fun text(s: String) = AssistantTurnCopy.Block("text", s)
    private fun tool(s: String) = AssistantTurnCopy.Block("tool_use", s)
    private fun thinking(s: String) = AssistantTurnCopy.Block("thinking", s)
    private fun info(s: String) = AssistantTurnCopy.Block("info", s)

    @Test
    fun `prose blocks join with a blank line`() {
        val out = AssistantTurnCopy.plainText(listOf(text("First."), text("Second.")))
        assertEquals("First.\n\nSecond.", out)
    }

    @Test
    fun `tool payloads are excluded`() {
        // A shell result can be tens of thousands of chars; including it would
        // bury the answer. The tool capsule has its own copy action.
        val out = AssistantTurnCopy.plainText(
            listOf(text("Before."), tool("{\"stdout\":\"...50k...\"}"), text("After.")),
        )
        assertEquals("Before.\n\nAfter.", out)
    }

    @Test
    fun `thinking is excluded by default and included on request`() {
        val blocks = listOf(thinking("reasoning"), text("answer"))
        assertEquals("answer", AssistantTurnCopy.plainText(blocks))
        assertEquals(
            "reasoning\n\nanswer",
            AssistantTurnCopy.plainText(blocks, includeThinking = true),
        )
    }

    @Test
    fun `info blocks are excluded`() {
        // Info blocks are UI notices (compact dividers, fallback notes), not
        // words the assistant said.
        val out = AssistantTurnCopy.plainText(listOf(info("Context compacted"), text("hi")))
        assertEquals("hi", out)
    }

    @Test
    fun `block order is preserved`() {
        val out = AssistantTurnCopy.plainText(listOf(text("1"), text("2"), text("3")))
        assertEquals("1\n\n2\n\n3", out)
    }

    @Test
    fun `blank and whitespace-only blocks are dropped`() {
        val out = AssistantTurnCopy.plainText(listOf(text("a"), text("   "), text(""), text("b")))
        assertEquals("a\n\nb", out)
    }

    @Test
    fun `each kept block is trimmed`() {
        val out = AssistantTurnCopy.plainText(listOf(text("  a  "), text("\nb\n")))
        assertEquals("a\n\nb", out)
    }

    @Test
    fun `legacy content is used when no prose block exists`() {
        val out = AssistantTurnCopy.plainText(
            blocks = listOf(tool("{}")),
            legacyContent = "old stored reply",
        )
        assertEquals("old stored reply", out)
    }

    @Test
    fun `legacy content is ignored when a prose block exists`() {
        // A migrated row carries the same words in both places; appending would
        // duplicate the whole reply.
        val out = AssistantTurnCopy.plainText(
            blocks = listOf(text("new")),
            legacyContent = "new",
        )
        assertEquals("new", out)
    }

    @Test
    fun `tool-only turn has nothing to copy`() {
        assertFalse(AssistantTurnCopy.hasCopyableText(listOf(tool("{}"), tool("{}"))))
        assertFalse(AssistantTurnCopy.hasCopyableText(emptyList()))
        assertFalse(AssistantTurnCopy.hasCopyableText(listOf(text("  "))))
    }

    @Test
    fun `turn with prose or legacy text is copyable`() {
        assertTrue(AssistantTurnCopy.hasCopyableText(listOf(tool("{}"), text("a"))))
        assertTrue(AssistantTurnCopy.hasCopyableText(emptyList(), legacyContent = "a"))
    }

    @Test
    fun `markdown is preserved verbatim`() {
        // The copy is the source text, not a rendered flattening: a user pasting
        // into an editor wants the fences and bullets back.
        val md = "Here:\n\n```kotlin\nval x = 1\n```\n\n- one\n- two"
        assertEquals(md, AssistantTurnCopy.plainText(listOf(text(md))))
    }
}
