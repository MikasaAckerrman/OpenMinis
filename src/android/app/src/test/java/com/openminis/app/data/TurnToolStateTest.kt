package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The veto that forbids an automatic replay needs the tool calls of the
 * turn that died - and only of that turn. These tests follow the order the
 * events really come in: the engine finishes a tool call first, the tool
 * result reaches the message list later.
 */
class TurnToolStateTest {

    @Test
    fun `a turn that starts has no tool calls`() {
        val state = TurnToolState()
        state.onTurnStart("m1")
        assertEquals("m1", state.assistantId)
        assertEquals(0, state.toolCalls)
    }

    @Test
    fun `a tool call of the engine is known before the message list has it`() {
        // The real order: the engine finishes the tool call, the result is
        // written to the message list after that. The decision must not
        // wait for the list - the tool already ran.
        val state = TurnToolState()
        state.onTurnStart("m1")
        state.onToolCallFinished()

        val toolBlocksByMessageId = mapOf("m1" to 0) // the list does not have it yet
        val hasToolCall = AutoResumePolicy.hasToolCallInDeadTurn(
            deadTurnMessageId = state.assistantId,
            toolBlocksByMessageId = toolBlocksByMessageId,
            engineToolCallsInDeadTurn = state.toolCalls,
        )
        assertTrue("a tool that already ran must forbid the replay", hasToolCall)
    }

    @Test
    fun `the tool calls of an earlier turn do not reach the next turn`() {
        val state = TurnToolState()
        state.onTurnStart("m1")
        state.onToolCallFinished()
        state.onToolCallFinished()

        // The next turn starts with a clean counter.
        state.onTurnStart("m2")
        assertEquals(0, state.toolCalls)

        val hasToolCall = AutoResumePolicy.hasToolCallInDeadTurn(
            deadTurnMessageId = state.assistantId,
            toolBlocksByMessageId = mapOf("m1" to 2, "m2" to 0),
            engineToolCallsInDeadTurn = state.toolCalls,
        )
        assertFalse("the calls of the old turn must not forbid the new one", hasToolCall)
    }

    @Test
    fun `a split moves the message id and keeps the calls of the turn`() {
        val state = TurnToolState()
        state.onTurnStart("m1")
        state.onToolCallFinished()

        state.onAssistantIdChanged("m1_split")

        assertEquals("m1_split", state.assistantId)
        assertEquals(1, state.toolCalls)
    }
}
