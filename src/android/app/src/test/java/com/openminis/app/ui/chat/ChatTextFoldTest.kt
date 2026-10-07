package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zcode-text-fold] The fold rule for a finished turn's text: the user
 * sees ONLY the final answer at rest — every text block that stands before
 * or between tool/thinking blocks folds into a capsule row; the trailing
 * text run (everything after the last tool/thinking block) stays visible.
 * Live turns never fold (the work is always shown as it happens).
 *
 * NOTE: this suite must execute on CI — see the [T-test-only-ci] marker in
 * .github/workflows/tests.yml; empty commits do not trigger workflows.
 */
class ChatTextFoldTest {

    private fun textBlock(id: String, content: String) = AssistantBlock(
        id = id,
        kind = "text",
        content = content,
    )

    private fun toolBlock(id: String) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = "",
        toolName = "shell_execute",
        toolStatus = ToolBlockStatus.SUCCESS,
    )

    private fun flatFor(vararg blocks: AssistantBlock, streaming: Boolean = false): List<FlatChatItem> =
        buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "m1",
                    role = "assistant",
                    content = "",
                    toolBlocks = blocks.toList(),
                    isStreaming = streaming,
                ),
            ),
        )

    @Test
    fun `text before tools folds, trailing text is the answer`() {
        val items = flatFor(
            textBlock("t1", "Проверяю лог устройства."),
            toolBlock("u1"),
            textBlock("t2", "Итог анализа."),
        )
        val md = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
        val t1 = md.first { it.parentBlockId == "t1" }
        val t2 = md.first { it.parentBlockId == "t2" }
        assertTrue("text before a tool must be marked intermediate (foldable)", t1.isIntermediateText)
        assertFalse("trailing text is the final answer — never folded", t2.isIntermediateText)
        assertFalse("finished message reports the raw streaming flag false", t1.parentMessageIsStreaming)
    }

    @Test
    fun `pure text message is the answer - nothing folds`() {
        val items = flatFor(textBlock("t1", "Просто ответ."))
        val t1 = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().single()
        assertFalse("no tools in the message — the whole text is the answer", t1.isIntermediateText)
    }

    @Test
    fun `message ending on tools folds its text`() {
        // No final text after the tool: everything textual is intermediate work.
        val items = flatFor(textBlock("t1", "Запускаю проверку."), toolBlock("u1"))
        val t1 = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().single()
        assertTrue("text preceding the trailing tool run folds", t1.isIntermediateText)
    }

    @Test
    fun `live turn keeps the fold gate closed`() {
        val items = flatFor(
            textBlock("t1", "Промежуточный текст."),
            toolBlock("u1"),
            streaming = true,
        )
        val t1 = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .first { it.parentBlockId == "t1" }
        assertTrue("marking is present even mid-stream (the renderer decides)", t1.isIntermediateText)
        assertTrue("but the raw streaming flag keeps the block visible while the turn runs",
            t1.parentMessageIsStreaming)
    }

    @Test
    fun `info rows do not make text intermediate`() {
        // "info" blocks are one-line system notices, not part of the answer's
        // narrative — a trailing text after an info row is still the answer.
        val info = AssistantBlock(id = "i1", kind = "info", content = "⟳ продолжение")
        val items = flatFor(info, textBlock("t1", "Финальный текст."))
        val t1 = items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>().single()
        assertFalse("info rows are exempt from the trailing-run rule", t1.isIntermediateText)
    }
}
