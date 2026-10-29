package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F7-hide-compacted-history] After a compact the chat must show the
 * divider plus the live tail - and NOTHING of the summarized rows,
 * tool folds included. The skip lives in buildFlatChatItems; this pins
 * it, including the invariant that the divider (a system row, never
 * marked) survives the filter and that an unmarked session is unchanged.
 */
class ChatFlatItemsCompactedTest {

    private fun msg(id: String, role: String, compacted: Boolean = false) =
        ChatMessage(id = id, role = role, content = "c-$id", isCompactedHistory = compacted)

    /** The real divider shape: a system row whose info block IS the summary. */
    private fun divider(id: String) = ChatMessage(
        id = id, role = "system", content = "",
        toolBlocks = listOf(
            AssistantBlock(id = "$id-b", kind = "info", content = "3 compressed"),
        ),
    )

    @Test
    fun `compacted rows are skipped while divider and live tail stay`() {
        val items = buildFlatChatItems(
            listOf(
                msg("u-old", "user", compacted = true),
                msg("a-old", "assistant", compacted = true),
                divider("sys-divider"),
                msg("u-live", "user"),
                msg("a-live", "assistant"),
            )
        )
        val userIds = items.filterIsInstance<FlatChatItem.UserBubble>().map { it.message.id }
        assertEquals(listOf("u-live"), userIds)
        assertTrue(
            "the divider (system, unmarked) must survive",
            items.any { it is FlatChatItem.AssistantInfo },
        )
    }

    @Test
    fun `unmarked session renders identically to before the skip`() {
        val plain = listOf(
            msg("u1", "user"),
            msg("a1", "assistant"),
            msg("u2", "user"),
        )
        val items = buildFlatChatItems(plain)
        assertEquals(
            "no compacted rows - nothing may be dropped",
            listOf("u1", "u2"),
            items.filterIsInstance<FlatChatItem.UserBubble>().map { it.message.id },
        )
    }
}
