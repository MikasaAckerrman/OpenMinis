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
    fun `text after tool blocks inserts before them`() {
        val r = reducer()
        r.reduce(AgentEvent.ToolUseStarted("c1", "shell_execute"))
        r.reduce(AgentEvent.ToolCallStarted("c1", "shell_execute", "Run Command"))
        r.reduce(AgentEvent.ToolCallFinished("c1", "shell_execute", true, "ok"))
        r.reduce(AgentEvent.TextDelta("after tools"))
        val blocks = r.currentBlocks()
        // text first, then the tool — the canonical wire order
        assertEquals("text", blocks[0].kind)
        assertEquals("tool_use", blocks[1].kind)
        assertEquals("after tools", blocks[0].content)
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
