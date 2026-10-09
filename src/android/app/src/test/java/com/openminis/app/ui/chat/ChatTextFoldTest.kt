package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zcode-turn-fold] THE fold rule (user's ZCode spec, 08.10): a finished
 * turn WITH tools folds as ONE unit — the flattener emits a single
 * AssistantTurnFold row at the first internal block; tools, thinking and
 * intermediate text all carry isTurnInternal and hide under that row at
 * rest. The trailing text run (the answer) and tool-less turns never fold.
 * Live turns never fold. Info rows are exempt.
 *
 * NOTE: this suite must execute on CI — see the [T-test-only-ci] marker in
 * .github/workflows/tests.yml; empty commits do not trigger workflows,
 * so this file's doc comment doubles as the run trigger.
 */
class ChatTextFoldTest {

    private fun textBlock(id: String, content: String) = AssistantBlock(
        id = id, kind = "text", content = content,
    )

    private fun toolBlock(id: String) = AssistantBlock(
        id = id, kind = "tool_use", content = "", toolName = "shell_execute",
        toolStatus = ToolBlockStatus.SUCCESS, durationMs = 1000L,
    )

    private fun thinkingBlock(id: String, content: String) = AssistantBlock(
        id = id, kind = "thinking", content = content,
    )

    private fun infoBlock(id: String) = AssistantBlock(
        id = id, kind = "info", content = "⟳ system notice",
    )

    private fun msg(
        id: String,
        vararg blocks: AssistantBlock,
        streaming: Boolean = false,
    ) = ChatMessage(
        id = id, role = "assistant", content = blocks.joinToString("") { it.content },
        toolBlocks = blocks.toList(), isStreaming = streaming,
    )

    private fun flat(vararg messages: ChatMessage) =
        buildFlatChatItems(messages = messages.toList(), sessionId = "s")

    @Test
    fun `turn with tools folds as one unit - row first, internals flagged, answer free`() {
        val items = flat(
            msg(
                "m1",
                textBlock("t1", "Проверяю лог."),
                toolBlock("u1"),
                textBlock("t2", "Финальный ответ."),
            ),
        )
        // ONE fold row, at the position of the first internal block.
        val fold = items.filterIsInstance<FlatChatItem.AssistantTurnFold>().single()
        assertEquals("m1", fold.messageId)
        assertEquals(1, fold.toolBlocks.size)
        val kinds = items.map { it.contentType }
        // [text, tool, text]: row rides DIRECTLY BEFORE the trailing
        // (answer) text — ZCode: collapsed view = [row][answer].
        assertEquals(listOf("header", "mdblock", "tool", "turnfold", "mdblock"), kinds)
        // Intermediate text + tool are internal; the answer is not.
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()[0].isTurnInternal)
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantToolUse>().single().isTurnInternal)
        assertFalse(items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()[1].isTurnInternal)
    }

    @Test
    fun `pure text turn never folds - whole message is the answer`() {
        val items = flat(msg("m1", textBlock("t1", "Просто ответ.")))
        assertNull(items.firstOrNull { it is FlatChatItem.AssistantTurnFold })
        assertFalse(items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().single().isTurnInternal)
    }

    @Test
    fun `thinking inside a tool turn folds too`() {
        val items = flat(
            msg(
                "m1",
                thinkingBlock("th1", "Размышляю."),
                toolBlock("u1"),
                textBlock("t1", "Ответ."),
            ),
        )
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantThinking>().single().isTurnInternal)
        assertEquals(1, items.filterIsInstance<FlatChatItem.AssistantTurnFold>().size)
    }

    @Test
    fun `info rows are exempt - fold row rides at the first real internal block`() {
        val items = flat(
            msg(
                "m1",
                infoBlock("i1"),
                textBlock("t1", "Работа."),
                toolBlock("u1"),
                textBlock("t2", "Ответ."),
            ),
        )
        val kinds = items.map { it.contentType }
        // info exempt; row still directly before the answer.
        assertEquals(listOf("header", "info", "mdblock", "tool", "turnfold", "mdblock"), kinds)
    }

    @Test
    fun `turn ending on tools gets the row at the cluster tail`() {
        val items = flat(
            msg(
                "m1",
                textBlock("t1", "Работаю."),
                toolBlock("u1"),
            ),
        )
        val kinds = items.map { it.contentType }
        // No trailing text: fallback emits the row after the loop.
        assertEquals(listOf("header", "mdblock", "tool", "turnfold"), kinds)
    }

    @Test
    fun `render filter drops folded internals and keeps the rest`() {
        val blocks = listOf(
            textBlock("t1", "Работа."),
            toolBlock("u1"),
            textBlock("t2", "Ответ."),
        )
        val items = flat(msg("m1", *blocks.toTypedArray()))
        // All internal at rest: only the header, the fold row and the
        // answer survive the filter.
        val visible = filterTurnInternalItems(items, emptyMap())
        assertEquals(
            listOf("header", "turnfold", "mdblock"),
            visible.map { it.contentType },
        )
        // Expanded: everything visible again.
        val expanded = filterTurnInternalItems(items, mapOf("m1" to true))
        assertEquals(items, expanded)
        // Live streaming: fast path — the list is returned as-is.
        val live = flat(msg("m1", *blocks.toTypedArray(), streaming = true))
        assertEquals(live, filterTurnInternalItems(live, emptyMap()))
    }

    @Test
    fun `live turn carries the streaming flag - the render gate stays closed`() {
        val items = flat(
            msg(
                "m1",
                textBlock("t1", "Работаю."),
                toolBlock("u1"),
                streaming = true,
            ),
        )
        // The fold row exists but is marked streaming — the renderer must
        // compose nothing from it while the turn runs (work is visible).
        val fold = items.filterIsInstance<FlatChatItem.AssistantTurnFold>().single()
        assertTrue(fold.messageIsStreaming)
        // Internals are flagged too — but the render gate checks the same
        // streaming flag before hiding anything.
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantToolUse>().single().isTurnInternal)
    }

    @Test
    fun `split turn folds as ONE group - leader row suppressed, continuation carries combined totals`() {
        // [T-inject-attach-group] User report 09.10 ("две кнопки — мусор"):
        // after an inject split the turn is two DB rows; each used to
        // render its OWN fold row. The group: leader A (hasSplitContinuation)
        // suppresses its own row; continuation B (continuationOf=A) renders
        // THE row with combined totals and memberIds for a single toggle.
        val a = msg("mA", toolBlock("ua1"), toolBlock("ua2"))
            .copy(hasSplitContinuation = true)
        val b = msg("mB", toolBlock("ub1"), textBlock("tb", "Ответ."))
            .copy(continuationOf = "mA")
        val items = flat(a, b)
        // ONE fold row for the WHOLE group — and it lives on B.
        val folds = items.filterIsInstance<FlatChatItem.AssistantTurnFold>()
        assertEquals(1, folds.size)
        val fold = folds.single()
        assertEquals("mB", fold.messageId)
        // Combined totals: A's 2 tools + B's 1 tool.
        assertEquals(3, fold.toolBlocks.size)
        // One toggle drives both segments' pills.
        assertEquals(listOf("mA", "mB"), fold.memberIds)
        // A's pills are internal — they hide under the group row at rest.
        assertTrue(items.filterIsInstance<FlatChatItem.AssistantToolUse>().all { it.isTurnInternal })
        // The single row rides directly before B's trailing answer; A
        // contributes pills only (no row of its own). Note: two CONSECUTIVE
        // assistant messages merge their header (neighbor lookback
        // precedededByUser=false) — in a real split a user message sits
        // between A and B and B does get its header; the group logic is
        // header-independent, so the fixture keeps them adjacent.
        val kinds = items.map { it.contentType }
        assertEquals(
            listOf("header", "tool", "tool", "tool", "turnfold", "mdblock"),
            kinds,
        )
        // The group filter: folded at rest, both keys flip together.
        val visible = filterTurnInternalItems(items, emptyMap())
        assertEquals(
            listOf("header", "turnfold", "mdblock"),
            visible.map { it.contentType },
        )
        val expanded = filterTurnInternalItems(items, mapOf("mA" to true, "mB" to true))
        assertEquals(items.size, expanded.size)
    }
}
