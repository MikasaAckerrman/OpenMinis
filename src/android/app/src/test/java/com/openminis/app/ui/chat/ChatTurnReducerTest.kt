package com.openminis.app.ui.chat

import com.openminis.app.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m11-reducer] Oracle tests for the turn reducer lifecycle — the
 * production ViewModel loop's documented block behavior as assertions.
 */
class ChatTurnReducerTest {

    private fun reducer() = ChatTurnReducer("a1", 0)

    @Test
    fun `tool result effect carries the execution duration`() {
        // [T-tool-duration-persist] Started→Finished must ride the
        // PersistToolResult effect into the row JSON — previously the
        // duration lived only in the block (reducer memory) and every
        // DB-restored tool card showed 0s.
        val r = reducer()
        r.reduce(AgentEvent.ToolCallStarted("d1", "shell_execute", "Run Command"))
        Thread.sleep(25)
        val effects = r.reduce(AgentEvent.ToolCallFinished("d1", "shell_execute", true, "ok"))
        val persist = effects.filterIsInstance<ChatTurnReducer.PersistToolResult>().single()
        assertTrue("duration should be measured, got ${persist.durationMs}", persist.durationMs >= 20)
        // …and the block's own duration matches the effect's.
        val block = r.currentBlocks().single { it.id == "d1" }
        assertEquals(persist.durationMs, block.durationMs)
    }

    @Test
    fun `tool result without a started block reports zero duration`() {
        // Defensive: a Finished for an unknown call id must not crash the
        // effect construction.
        val r = reducer()
        val effects = r.reduce(AgentEvent.ToolCallFinished("ghost", "grep", true, "ok"))
        val persist = effects.filterIsInstance<ChatTurnReducer.PersistToolResult>().single()
        assertEquals(0L, persist.durationMs)
    }

    @Test
    fun `full turn - thinking, text, tool, finish`() {
        val r = reducer()
        // reasoning streams
        r.reduce(AgentEvent.ThinkingDelta("step 1. "))
        r.reduce(AgentEvent.ThinkingDelta("step 2."))
        var blocks = r.currentBlocks()
        assertEquals(1, blocks.size)
        assertEquals("thinking_0", blocks[0].id)
        assertEquals("thinking", blocks[0].kind)
        assertEquals("step 1. step 2.", blocks[0].content)
        assertNull(blocks[0].toolStatus)
        // answer text starts: thinking card seals
        r.reduce(AgentEvent.TextDelta("Answer: "))
        r.reduce(AgentEvent.TextDelta("42"))
        blocks = r.currentBlocks()
        assertEquals(ToolBlockStatus.SUCCESS, blocks[0].toolStatus)
        assertEquals("Answer: 42", r.text.toString())
        assertEquals("text", blocks[1].kind)
        // tool round
        r.reduce(AgentEvent.ToolUseStarted("c1", "file_read"))
        r.reduce(AgentEvent.ToolInputDelta("c1", "{\"pa"))
        r.reduce(AgentEvent.ToolInputDelta("c1", "th\":\"/a\"}"))
        blocks = r.currentBlocks()
        val tool = blocks[2]
        assertEquals(ToolBlockStatus.PENDING, tool.toolStatus)
        assertEquals("{\"path\":\"/a\"}", tool.toolArgs)
        r.reduce(AgentEvent.ToolCallStarted("c1", "file_read", "Read File"))
        assertEquals(ToolBlockStatus.RUNNING, r.currentBlocks()[2].toolStatus)
        val effects = r.reduce(
            AgentEvent.ToolCallFinished("c1", "file_read", true, "3 lines"),
        )
        assertEquals(ToolBlockStatus.SUCCESS, r.currentBlocks()[2].toolStatus)
        assertEquals("3 lines", r.currentBlocks()[2].content)
        // effects: UI dirty + a persisted tool result
        assertTrue(effects[0] is ChatTurnReducer.UiDirty)
        val persist = effects[1] as ChatTurnReducer.PersistToolResult
        assertEquals("c1", persist.callId)
        assertEquals(false, persist.isError)
        // finish adds no blocks
        r.reduce(AgentEvent.TurnFinished("stop"))
        assertEquals(3, r.currentBlocks().size)
    }

    @Test
    fun `thinking card seals when a tool starts without text`() {
        val r = reducer()
        r.reduce(AgentEvent.ThinkingDelta("reasoning only"))
        org.junit.Assert.assertNull(r.currentBlocks()[0].toolStatus)
        r.reduce(AgentEvent.ToolUseStarted("c1", "file_read"))
        // the DeepSeek pattern: reasoning -> tool, no answer text
        assertEquals(ToolBlockStatus.SUCCESS, r.currentBlocks()[0].toolStatus)
    }

    @Test
    fun `text after finished tool is the next round - appends after it`() {
        val r = reducer()
        r.reduce(AgentEvent.ToolUseStarted("c1", "shell_execute"))
        r.reduce(AgentEvent.ToolCallStarted("c1", "shell_execute", "Run Command"))
        r.reduce(AgentEvent.ToolCallFinished("c1", "shell_execute", true, "ok"))
        r.reduce(AgentEvent.TextDelta("next round answer"))
        val blocks = r.currentBlocks()
        // [T-round-text-order] A FINISHED tool means the round boundary
        // passed — this text belongs to the NEXT round and keeps its
        // natural position AFTER the tool. (The pre-fix expectation — text
        // hoisted above the finished tool — was exactly the "текст сверху,
        // тула снизу" scramble the 07.10 report describes: every round's
        // answer rendered above ALL tools.)
        assertEquals("tool_use", blocks[0].kind)
        assertEquals("text", blocks[1].kind)
        assertEquals("next round answer", blocks[1].content)
    }

    @Test
    fun `same-response trailing text still lands before its pending tools`() {
        val r = reducer()
        // qwen-style content-after-tool_calls chunking: the tool is still
        // PENDING (its result cannot exist — the response hasn't ended),
        // so the trailing content is the SAME response's {content} — the
        // canonical wire shape puts it before the tool.
        r.reduce(AgentEvent.ToolUseStarted("c1", "web_search"))
        r.reduce(AgentEvent.TextDelta("trailing content"))
        val blocks = r.currentBlocks()
        assertEquals("text", blocks[0].kind)
        assertEquals("tool_use", blocks[1].kind)
        assertEquals(ToolBlockStatus.PENDING, blocks[1].toolStatus)
        assertEquals("trailing content", blocks[0].content)
    }

    @Test
    fun `multi-round turn keeps text between tools - narrative order`() {
        val r = reducer()
        // Round 1: text + tool call, tool executes and finishes
        r.reduce(AgentEvent.TextDelta("checking the log"))
        r.reduce(AgentEvent.ToolUseStarted("t1", "shell_execute"))
        r.reduce(AgentEvent.ToolCallFinished("t1", "shell_execute", true, "done"))
        // Round 2: answer text + second tool, finishes
        r.reduce(AgentEvent.TextDelta("found it, now writing"))
        r.reduce(AgentEvent.ToolUseStarted("t2", "file_write"))
        r.reduce(AgentEvent.ToolCallFinished("t2", "file_write", true, "written"))
        // Round 3: final answer
        r.reduce(AgentEvent.TextDelta("all done"))
        val blocks = r.currentBlocks()
        val shape = blocks.joinToString("|") { it.kind }
        // The exact narrative: each text sits where it was emitted —
        // between the tool rounds it followed and precedes. Pre-fix this
        // collapsed to [text|text|text|tool|tool] (all text merged at top).
        assertEquals("text|tool_use|text|tool_use|text", shape)
        assertEquals("checking the log", blocks[0].content)
        assertEquals("found it, now writing", blocks[2].content)
        assertEquals("all done", blocks[4].content)
        // The whole-turn mirror still carries every segment's text
        assertEquals("checking the logfound it, now writingall done", r.text.toString())
    }

    @Test
    fun `rounds of thinking sum into one block and seal on text`() {
        val r = reducer()
        r.reduce(AgentEvent.ThinkingDelta("think one"))
        r.reduce(AgentEvent.ToolUseStarted("t1", "shell_execute"))
        r.reduce(AgentEvent.ToolCallFinished("t1", "shell_execute", true, "ok"))
        // Round 2's reasoning continues the SAME thinking card (summed)
        r.reduce(AgentEvent.ThinkingDelta("think two"))
        r.reduce(AgentEvent.TextDelta("answer"))
        val blocks = r.currentBlocks()
        val thinkingBlocks = blocks.filter { it.kind == "thinking" }
        assertEquals(1, thinkingBlocks.size)
        assertEquals("think onethink two", thinkingBlocks[0].content)
        // sealed the moment the answer text flows
        assertEquals(ToolBlockStatus.SUCCESS, thinkingBlocks[0].toolStatus)
    }

    @Test
    fun `failed tool marks FAILED and persists isError`() {
        val r = reducer()
        r.reduce(AgentEvent.ToolUseStarted("c9", "file_write"))
        r.reduce(AgentEvent.ToolCallStarted("c9", "file_write", "Write File"))
        val effects = r.reduce(
            AgentEvent.ToolCallFinished("c9", "file_write", false, "error: disk"),
        )
        assertEquals(ToolBlockStatus.FAILED, r.currentBlocks()[0].toolStatus)
        val persist = effects[1] as ChatTurnReducer.PersistToolResult
        assertTrue(persist.isError)
    }

    @Test
    fun `ui effect carries the accumulated state`() {
        val r = reducer()
        val effects = r.reduce(AgentEvent.TextDelta("hello"))
        val ui = effects[0] as ChatTurnReducer.UiDirty
        assertEquals("a1", ui.assistantId)
        assertEquals("hello", ui.text)
        assertEquals(1, ui.blocks.size)
    }
}
