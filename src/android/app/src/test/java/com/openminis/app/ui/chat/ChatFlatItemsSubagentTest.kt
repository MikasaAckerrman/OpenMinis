package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B34] The no-collapse contract (replaces the [B6] batch-aggregation
 * test): N `spawn_subagent` blocks render as N individual
 * [FlatChatItem.AssistantToolUse] items — ZCode-style dark cards in the
 * stream, never a collapsed "Субагенты (X/Y)" table (user complaint J9).
 * Interleaved text keeps its place, statuses ride the blocks untouched,
 * and a settled turn still EMITS its agent items (the AssistantTurnFold
 * row owns the at-rest compactness — rendering, not aggregation).
 */
class ChatFlatItemsSubagentTest {

    private fun agentBlock(
        id: String,
        role: String,
        status: ToolBlockStatus,
        result: String = "",
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = AGENT_TOOL,
        toolTitle = role,
        toolStatus = status,
        toolArgs = org.json.JSONObject().put("role", role).put("task", "do $role things").toString(),
        content = result,
    )

    private fun message(vararg blocks: AssistantBlock, streaming: Boolean = false) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = "",
        isStreaming = streaming,
        toolBlocks = blocks.toList(),
    )

    private fun agentItems(items: List<FlatChatItem>) = items
        .filterIsInstance<FlatChatItem.AssistantToolUse>()
        .filter { it.block.toolName == AGENT_TOOL }

    @Test
    fun `a run of three agents stays three individual cards`() {
        val items = buildFlatChatItems(
            listOf(
                message(
                    agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS, "analysis done"),
                    agentBlock("a2", "coder", ToolBlockStatus.RUNNING),
                    agentBlock("a3", "reviewer", ToolBlockStatus.FAILED, "compile error"),
                    streaming = true,
                ),
            ),
        )
        val agents = agentItems(items)
        assertEquals(3, agents.size)
        assertEquals(listOf("a1", "a2", "a3"), agents.map { it.block.id })
        // Statuses ride the blocks untouched — the chips are derived, not copied.
        assertEquals(ToolBlockStatus.SUCCESS, agents[0].block.toolStatus)
        assertEquals(ToolBlockStatus.RUNNING, agents[1].block.toolStatus)
        assertEquals(ToolBlockStatus.FAILED, agents[2].block.toolStatus)
    }

    @Test
    fun `interleaved commentary leaves every agent its own card`() {
        val msg = ChatMessage(
            id = "m1",
            role = "assistant",
            content = "",
            isStreaming = true,
            toolBlocks = listOf(
                agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS),
                AssistantBlock(id = "txt1", kind = "text", content = "context note"),
                agentBlock("a2", "coder", ToolBlockStatus.RUNNING),
                agentBlock("a3", "reviewer", ToolBlockStatus.RUNNING),
            ),
        )
        val items = buildFlatChatItems(listOf(msg))
        // 3 agents, each its own item, in block order — no grouping of any kind.
        val agents = agentItems(items)
        assertEquals(listOf("a1", "a2", "a3"), agents.map { it.block.id })
        // The text row is still where the model put it — between a1 and a2.
        val textIdx = items.indexOfFirst { it is FlatChatItem.AssistantText }
        assertTrue("the interleaved text must render as its own row", textIdx >= 0)
        val a1Idx = items.indexOf(agents[0])
        val a2Idx = items.indexOf(agents[1])
        assertTrue(a1Idx < textIdx && textIdx < a2Idx)
    }

    @Test
    fun `a settled turn still emits its agent items for the fold row`() {
        // At rest the AssistantTurnFold owns compactness (render-time);
        // the items themselves must exist so the fold can expand back.
        val items = buildFlatChatItems(
            listOf(
                message(
                    agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS),
                    agentBlock("a2", "coder", ToolBlockStatus.SUCCESS),
                ),
            ),
        )
        assertEquals(2, agentItems(items).size)
    }

    @Test
    fun `non-agent tools are untouched by the subagent card path`() {
        val items = buildFlatChatItems(
            listOf(
                message(
                    AssistantBlock(id = "s1", kind = "tool_use", toolName = "shell_execute"),
                    AssistantBlock(id = "s2", kind = "tool_use", toolName = "file_read"),
                ),
            ),
        )
        assertEquals(2, items.filterIsInstance<FlatChatItem.AssistantToolUse>().size)
        assertTrue(agentItems(items).isEmpty())
    }
}
