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
        assertEquals(listOf("header", "turnfold", "mdblock", "tool", "mdblock"), kinds)
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
        assertEquals(listOf("header", "info", "turnfold", "mdblock", "tool", "mdblock"), kinds)
        // The order assertion above IS the proof: the fold row skipped the
        // info block and rode at the first real internal (text) block.
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
}
