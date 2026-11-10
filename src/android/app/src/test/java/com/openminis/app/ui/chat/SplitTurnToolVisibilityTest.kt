package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B47-split-leader-visible] The reviewer's acceptance pins, task 2: a
 * user message sent MID-RUN splits the turn into leader A + continuation
 * B, and the old fold-group design marked A's blocks turn-internal while
 * ALSO suppressing A's fold row — the cards vanished the moment the
 * inject landed (A hid them; empty B had drawn nothing). The leader's
 * cards now survive the fold filter in every status, the turn still
 * renders exactly ONE fold row (under B — the "две кнопки — мусор" rule
 * holds), and a plain non-split turn keeps its old collapse behavior.
 */
class SplitTurnToolVisibilityTest {

    private fun toolBlock(id: String, status: ToolBlockStatus) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = "",
        toolStatus = status,
        toolName = "shell_execute",
        toolTitle = "Run checks",
    )

    /** Leader A: frozen by the split (isStreaming=false), work in flight. */
    private fun leader() = ChatMessage(
        id = "a1",
        role = "assistant",
        content = "working",
        isStreaming = false,
        hasSplitContinuation = true,
        toolBlocks = listOf(
            AssistantBlock(id = "a1-t", kind = "text", content = "Running checks"),
            toolBlock("a1-s1", ToolBlockStatus.RUNNING),
            toolBlock("a1-s2", ToolBlockStatus.SUCCESS),
        ),
    )

    /** Continuation B: created by the split — empty and streaming. */
    private fun continuation() = ChatMessage(
        id = "a2",
        role = "assistant",
        content = "",
        isStreaming = true,
        isAwaitingModelResponse = true,
        continuationOf = "a1",
    )

    private fun splitTurnMessages() = listOf(
        leader(),
        ChatMessage(id = "u1", role = "user", content = "а как дела?"),
        continuation(),
    )

    @Test
    fun `the split leader keeps its tool cards visible after an inject`() {
        val visible = filterTurnInternalItems(buildFlatChatItems(splitTurnMessages()), emptyMap())
        val toolIds = visible.filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.block.id }
        assertTrue(
            "a RUNNING leader tool must survive the fold filter",
            "a1-s1" in toolIds,
        )
        assertTrue(
            "a COMPLETED leader tool must survive the fold filter",
            "a1-s2" in toolIds,
        )
        // The leader's mid-turn text survives with the same rationale: it
        // belongs to a row that never emits a fold of its own.
        assertTrue(
            visible.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().isNotEmpty(),
        )
    }

    @Test
    fun `the split turn still renders exactly one fold row under the continuation`() {
        val items = buildFlatChatItems(splitTurnMessages())
        val folds = items.filterIsInstance<FlatChatItem.AssistantTurnFold>()
        assertEquals("one toggle for the whole split turn", 1, folds.size)
        assertEquals("a2", folds[0].messageId.substringBefore('#'))
        // The row still totals the WHOLE turn (leader + continuation tools).
        assertTrue(folds[0].toolBlocks.any { it.id == "a1-s1" })
    }

    @Test
    fun `a non-split folding turn keeps its old collapse at rest`() {
        // Regression guard: the leader exemption must not leak into plain
        // finished turns — their tools still hide under the fold row.
        val msg = ChatMessage(
            id = "a0",
            role = "assistant",
            content = "",
            isStreaming = false,
            toolBlocks = listOf(
                toolBlock("a0-t1", ToolBlockStatus.SUCCESS),
                AssistantBlock(id = "a0-t2", kind = "text", content = "done"),
            ),
        )
        val items = buildFlatChatItems(listOf(msg))
        assertEquals(1, items.filterIsInstance<FlatChatItem.AssistantTurnFold>().size)
        val visible = filterTurnInternalItems(items, emptyMap())
        assertEquals(
            "a finished non-split turn's tools hide at rest",
            emptyList(),
            visible.filterIsInstance<FlatChatItem.AssistantToolUse>().map { it.block.id },
        )
    }
}
