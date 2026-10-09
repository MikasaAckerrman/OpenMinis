package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engine-queue-interrupt] Mid-turn queue injection (iOS d14174d3
 * parity). The user report that drove it: on the legacy path a queued
 * message starts working the MOMENT a tool closes; the engine path ran
 * its whole plan to convergence and only then drained the queue. The
 * user watched their message sit ignored while the agent kept working.
 *
 * Contract under test: a queued message returned by the poll lands in
 * the working history AFTER the tool results of the completed round —
 * the next model call SEES it and can answer it within the same turn.
 */
class EngineQueueInterruptTest {

    private class RecordingGateway(private val script: List<List<StreamEvent>>) : ModelGateway {
        override val modelId = "fake"
        private var index = 0
        val requests = mutableListOf<List<EngineMessage>>()
        override fun stream(
            messages: List<EngineMessage>,
            tools: List<AgentToolDefinition>,
            maxTokens: Int,
        ) = flow {
            requests.add(messages)
            val events = script[index]
            index++
            for (e in events) emit(e)
        }
    }

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(object : EngineTool {
            override val name = "file_read"
            override val mutation = MutationKind.READ
            override fun definition() = AgentToolDefinition(
                name = "file_read",
                description = "Read",
                parameters = mapOf("path" to AgentToolParam(type = "string", description = "p")),
                required = listOf("path"),
            )
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                ToolExecutionResult("ok", true)
        })
    }

    @Test
    fun `queued message injected between rounds reaches the next model call`() = runTest {
        val gw = RecordingGateway(
            listOf(
                // Round 1: model calls a tool, then round 2 answers.
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.TextDelta("answer"), StreamEvent.Done),
            ),
        )
        var polled = false
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("body", true)
        }
        val events = loop.runTurn(
            TurnInput(
                sessionId = "s",
                userText = "read a",
                history = emptyList(),
                mode = PermissionMode.AUTO,
                onPendingUserMessage = {
                    if (!polled) { polled = true; "STOP using tools, just answer" } else null
                },
            ),
        ).toList()

        assertEquals(2, gw.requests.size)
        // The second request (after the tool round) carries the queued
        // message as a USER message AFTER the tool result — the model
        // sees it and can respond to it in this same turn.
        val second = gw.requests[1]
        val injectedIdx = second.indexOfLast { it.role == EngineRole.USER && it.text.contains("STOP using tools") }
        assertTrue("injected user message missing from second request", injectedIdx >= 0)
        val toolIdx = second.indexOfLast { it.role == EngineRole.TOOL }
        assertTrue("injection must follow the tool result", injectedIdx > toolIdx)
        // Turn completed normally.
        assertTrue(events.last() is AgentEvent.TurnFinished)
        assertEquals(1, events.count { it is AgentEvent.TurnFinished })
    }
}
