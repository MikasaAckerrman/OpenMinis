package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m7-agent-loop] Headless round-structure tests against a scripted
 * fake gateway — the M8 wiring will point production at this loop.
 */
class EngineAgentLoopTest {

    private class ScriptedGateway(private val script: List<List<StreamEvent>>) : ModelGateway {
        override val modelId = "fake"
        private var index = 0
        override fun stream(
            messages: List<EngineMessage>,
            tools: List<AgentToolDefinition>,
            maxTokens: Int,
        ) = flow {
            val events = script[index]
            index++
            for (e in events) emit(e)
        }
    }

    private val fileReadDef = AgentToolDefinition(
        name = "file_read",
        description = "Read",
        parameters = mapOf("path" to AgentToolParam(type = "string", description = "p")),
        required = listOf("path"),
    )

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(object : EngineTool {
            override val name = "file_read"
            override val mutation = MutationKind.READ
            override fun definition() = fileReadDef
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("ok", true)
        })
    }

    private fun turnInput(history: List<EngineMessage> = emptyList()) = TurnInput(
        sessionId = "s1",
        userText = "hi",
        history = history,
        mode = PermissionMode.AUTO,
    )

    @Test
    fun `text-only turn stops after one round`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.TextDelta("hello"),
                    StreamEvent.Usage(10, 5),
                    StreamEvent.Done,
                ),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "hello" })
        assertTrue(events.last() is AgentEvent.TurnFinished)
        assertEquals("stop", (events.last() as AgentEvent.TurnFinished).reason)
    }

    @Test
    fun `tool round executes and the second round stops`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.TextDelta("done"), StreamEvent.Done),
            ),
        )
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("file body", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(1, executed)
        val finished = events.last() as AgentEvent.TurnFinished
        assertEquals("stop", finished.reason)
        val toolEvent = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(toolEvent.success)
        assertEquals("file body", toolEvent.summary)
    }

    @Test
    fun `parallel-safe batch runs concurrently`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.ToolCall(EngineToolCall("c2", "file_read", "{\"path\":\"/b.kt\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val start = java.util.concurrent.atomic.AtomicInteger(0)
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            // both must overlap: signal entry, wait for the partner
            if (start.incrementAndGet() == 1) {
                var waited = 0
                while (start.get() == 1 && waited < 500) {
                    Thread.sleep(2); waited += 2
                }
            }
            EngineAgentLoop.ToolOutcome("x", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(2, start.get())
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
    }

    @Test
    fun `gateway failure ends the turn as recoverable error`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.TextDelta("par"),
                    StreamEvent.Failure("connection closed", recoverable = true),
                ),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        val err = events.last() as AgentEvent.Error
        assertTrue(err.recoverable)
    }

    @Test
    fun `preflight-blocked call becomes a failed tool result`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    // missing the required "path"
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("never", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(0, executed) // blocked BEFORE the executor
        val toolEvent = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(!toolEvent.success)
        assertTrue(toolEvent.summary.contains("missing required parameter"))
    }

    @Test
    fun `round limit fires TurnFinished`() = runTest {
        // every round issues one more tool call — never stops on its own
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                emit(StreamEvent.ToolCall(EngineToolCall("c${messages.size}", "file_read", "{\"path\":\"/a.kt\"}")))
                emit(StreamEvent.Done)
            }
        }
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("x", true)
        }
        val events = loop.runTurn(
            turnInput().copy(limits = LoopLimits(maxRounds = 3)),
        ).toList()
        val finished = events.last() as AgentEvent.TurnFinished
        assertEquals("round_limit", finished.reason)
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
    }
}
