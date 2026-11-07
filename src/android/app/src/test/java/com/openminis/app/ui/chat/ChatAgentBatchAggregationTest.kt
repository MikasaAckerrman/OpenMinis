package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B6] The batch-aggregation contract: maximal CONSECUTIVE runs of
 * per-agent rows collapse into one SubagentBatch card; a lone agent row
 * keeps its pill; ANY interleaved item breaks the run (the orchestrator's
 * commentary stays exactly where the model put it); statuses ride the
 * blocks untouched.
 */
class ChatAgentBatchAggregationTest {

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

    private fun agentPill(
        messageId: String,
        block: AssistantBlock,
        streaming: Boolean = false,
    ) = FlatChatItem.AssistantToolUse(
        messageId = messageId,
        block = block,
        allToolBlocks = listOf(block),
        messageIsStreaming = streaming,
    )

    private fun toolPill(id: String) = FlatChatItem.AssistantToolUse(
        messageId = "m1",
        block = AssistantBlock(id = id, kind = "tool_use", toolName = "shell_execute"),
        allToolBlocks = emptyList(),
    )

    @Test
    fun `a run of three agents collapses into one batch card`() {
        val items = listOf(
            agentPill("m1", agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS, "analysis done")),
            agentPill("m1", agentBlock("a2", "coder", ToolBlockStatus.RUNNING)),
            agentPill("m1", agentBlock("a3", "reviewer", ToolBlockStatus.FAILED, "compile error")),
        )
        val out = aggregateAgentBatchItems(items)
        assertEquals(1, out.size)
        val batch = out.single() as FlatChatItem.SubagentBatch
        assertEquals(listOf("a1", "a2", "a3"), batch.blocks.map { it.id })
        // Statuses ride the blocks untouched — the chips are derived, not copied.
        assertEquals(ToolBlockStatus.SUCCESS, batch.blocks[0].toolStatus)
        assertEquals(ToolBlockStatus.RUNNING, batch.blocks[1].toolStatus)
        assertEquals(ToolBlockStatus.FAILED, batch.blocks[2].toolStatus)
    }

    @Test
    fun `progress label counts settled agents of the batch`() {
        val batch = aggregateAgentBatchItems(
            listOf(
                agentPill("m1", agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS)),
                agentPill("m1", agentBlock("a2", "coder", ToolBlockStatus.RUNNING)),
                agentPill("m1", agentBlock("a3", "reviewer", ToolBlockStatus.FAILED)),
            ),
        ).single() as FlatChatItem.SubagentBatch
        // DONE + ERROR are both settled; only RUNNING stays in flight.
        assertEquals("Субагенты (2/3 завершено)", batchProgressLabel(batch.blocks))
    }

    @Test
    fun `a lone agent row keeps its pill (one agent is not a batch)`() {
        val pill = agentPill("m1", agentBlock("a1", "coder", ToolBlockStatus.RUNNING))
        val out = aggregateAgentBatchItems(listOf(pill))
        assertEquals(1, out.size)
        assertTrue(out.single() is FlatChatItem.AssistantToolUse)
    }

    @Test
    fun `interleaved commentary breaks the run into two groups`() {
        val text = FlatChatItem.AssistantText(
            messageId = "m1",
            block = AssistantBlock(id = "txt1", kind = "text"),
            isStreaming = false,
            messageMarkdown = "context note",
        )
        val out = aggregateAgentBatchItems(
            listOf(
                agentPill("m1", agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS)),
                text,
                agentPill("m1", agentBlock("a2", "coder", ToolBlockStatus.RUNNING)),
                agentPill("m1", agentBlock("a3", "reviewer", ToolBlockStatus.RUNNING)),
            ),
        )
        // text | batch(2) — the lone first pill stays a pill.
        assertEquals(3, out.size)
        assertTrue(out[0] is FlatChatItem.AssistantToolUse)
        assertTrue(out[1] is FlatChatItem.AssistantText)
        val batch = out[2] as FlatChatItem.SubagentBatch
        assertEquals(listOf("a2", "a3"), batch.blocks.map { it.id })
    }

    @Test
    fun `non-agent tools are never collapsed`() {
        val out = aggregateAgentBatchItems(
            listOf(toolPill("s1"), toolPill("s2"), toolPill("s3")),
        )
        assertEquals(3, out.size)
        out.forEach { assertTrue(it is FlatChatItem.AssistantToolUse) }
    }

    @Test
    fun `streaming flag is propagated from any member of the run`() {
        val out = aggregateAgentBatchItems(
            listOf(
                agentPill("m1", agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS)),
                agentPill("m1", agentBlock("a2", "coder", ToolBlockStatus.RUNNING), streaming = true),
            ),
        )
        assertTrue((out.single() as FlatChatItem.SubagentBatch).messageIsStreaming)
    }

    @Test
    fun `agent blocks between other tools produce two batches with distinct keys`() {
        val out = aggregateAgentBatchItems(
            listOf(
                agentPill("m1", agentBlock("a1", "analyst", ToolBlockStatus.SUCCESS)),
                agentPill("m1", agentBlock("a2", "coder", ToolBlockStatus.SUCCESS)),
                toolPill("s1"),
                agentPill("m1", agentBlock("a3", "reviewer", ToolBlockStatus.SUCCESS)),
                agentPill("m1", agentBlock("a4", "tester", ToolBlockStatus.SUCCESS)),
            ),
        )
        val batches = out.filterIsInstance<FlatChatItem.SubagentBatch>()
        assertEquals(2, batches.size)
        assertNotEquals(batches[0].key, batches[1].key)
    }

    @Test
    fun `a list without agent rows passes through untouched`() {
        val items = listOf<FlatChatItem>(toolPill("s1"), toolPill("s2"))
        assertTrue(aggregateAgentBatchItems(items) === items)
        assertTrue(aggregateAgentBatchItems(emptyList()).isEmpty())
    }

    @Test
    fun `subagent blocks are exempt from being mistaken for generic tools`() {
        // The discriminator is the toolName (AGENT_TOOL == spawn_subagent) —
        // a generic tool with a similar title must not join a batch.
        val lookalike = FlatChatItem.AssistantToolUse(
            messageId = "m1",
            block = AssistantBlock(
                id = "t1",
                kind = "tool_use",
                toolName = "shell_execute",
                toolTitle = AGENT_TOOL,
            ),
            allToolBlocks = emptyList(),
        )
        val out = aggregateAgentBatchItems(
            listOf(
                agentPill("m1", agentBlock("a1", "coder", ToolBlockStatus.RUNNING)),
                lookalike,
            ),
        )
        // lookalike breaks the run → pill(1) + shell pill.
        assertEquals(2, out.size)
        assertTrue(out[0] is FlatChatItem.AssistantToolUse)
        assertTrue(out[1] is FlatChatItem.AssistantToolUse)
        assertFalse(out[1] is FlatChatItem.SubagentBatch)
    }
}
