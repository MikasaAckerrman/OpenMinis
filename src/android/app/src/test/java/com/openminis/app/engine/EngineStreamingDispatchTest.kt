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
 * [PARALLEL_TOOL_ENGINE_SPEC Опора 1] Streaming speculative dispatch:
 * a tool call launches into the gates the moment its args JSON closes —
 * while the model is still generating — instead of after the whole
 * response lands. The timing proofs use real threads on
 * Dispatchers.Default against a blocked gateway stream (the same
 * real-time pattern as the overlap tests in EngineAgentLoopTest).
 */
class EngineStreamingDispatchTest {

    private val fileReadDef = AgentToolDefinition(
        name = "file_read",
        description = "Read",
        parameters = mapOf("path" to AgentToolParam(type = "string", description = "p")),
        required = listOf("path"),
    )

    private fun registry() = ToolRegistry().apply {
        register(object : EngineTool {
            override val name = "file_read"
            override val mutation = MutationKind.READ
            override fun definition() = fileReadDef
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("ok", true)
        })
        register(object : EngineTool {
            override val name = "file_write"
            override val mutation = MutationKind.WRITE
            override fun definition() = fileReadDef.copy(name = "file_write")
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("written", true)
        })
    }

    private fun turnInput() = TurnInput(
        sessionId = "s1",
        userText = "hi",
        history = emptyList(),
        mode = PermissionMode.AUTO,
    )

    @Test
    fun `a closed tool call executes while the model is still generating`() = runTest {
        val readStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        var doneEmittedAtNs = 0L
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                emit(StreamEvent.ToolCall(EngineToolCall("c1", "file_read", """{"path":"/a.kt"}""")))
                // The model "keeps talking": block the stream while the
                // speculative dispatch has every chance to start the read.
                Thread.sleep(400)
                doneEmittedAtNs = System.nanoTime()
                emit(StreamEvent.Done)
            }
        }
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            readStarted.set(true)
            EngineAgentLoop.ToolOutcome("ok", true)
        }
        loop.runTurn(turnInput()).toList()
        assertTrue("the read must have STARTED before the stream finished", readStarted.get())
        // And the marker was set before Done: the executor runs on a
        // real thread; the gateway blocked 400 ms — the ordering is the
        // whole point, so re-check against the timestamp too.
        assertTrue(doneEmittedAtNs > 0)
    }

    @Test
    fun `a write streamed mid-generation waits for the earlier in-flight read`() {
        runTest {
            val stamps = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val gw = object : ModelGateway {
                override val modelId = "fake"
                override fun stream(
                    messages: List<EngineMessage>,
                    tools: List<AgentToolDefinition>,
                    maxTokens: Int,
                ) = flow {
                    emit(StreamEvent.ToolCall(EngineToolCall("c1", "file_read", """{"path":"/a.kt"}""")))
                    // The read is now IN FLIGHT (its executor sleeps below);
                    // the model "decides" to write the same file next.
                    Thread.sleep(300)
                    emit(StreamEvent.ToolCall(EngineToolCall("c2", "file_write", """{"path":"/a.kt"}""")))
                    emit(StreamEvent.Done)
                }
            }
            val loop = EngineAgentLoop(gw, registry()) { name, _ ->
                stamps["${name}:start"] = System.nanoTime()
                Thread.sleep(400) // widen the read's in-flight window
                stamps["${name}:end"] = System.nanoTime()
                EngineAgentLoop.ToolOutcome("ok:$name", true)
            }
            val events = loop.runTurn(turnInput()).toList()
            assertEquals(2, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
            // WAR (spec §4.2.3): the write started only AFTER the read
            // finished, even though its call arrived while the read was
            // still running.
            assertTrue(
                "read must start before write",
                stamps["file_read:start"]!! < stamps["file_write:start"]!!,
            )
            assertTrue(
                "write must wait for the read's END (WAR)",
                stamps["file_read:end"]!! <= stamps["file_write:start"]!!,
            )
        }
    }

    @Test
    fun `independent reads streamed one by one still overlap`() {
        runTest {
            val started = java.util.concurrent.atomic.AtomicInteger(0)
            val gw = object : ModelGateway {
                override val modelId = "fake"
                override fun stream(
                    messages: List<EngineMessage>,
                    tools: List<AgentToolDefinition>,
                    maxTokens: Int,
                ) = flow {
                    emit(StreamEvent.ToolCall(EngineToolCall("c1", "file_read", """{"path":"/a.kt"}""")))
                    Thread.sleep(60)
                    emit(StreamEvent.ToolCall(EngineToolCall("c2", "file_read", """{"path":"/b.kt"}""")))
                    Thread.sleep(60)
                    emit(StreamEvent.ToolCall(EngineToolCall("c3", "file_read", """{"path":"/c.kt"}""")))
                    emit(StreamEvent.Done)
                }
            }
            val loop = EngineAgentLoop(gw, registry()) { _, _ ->
                started.incrementAndGet()
                var waited = 0
                while (started.get() < 3 && waited < 4000) {
                    Thread.sleep(5); waited += 5
                }
                EngineAgentLoop.ToolOutcome("ok", true)
            }
            val events = loop.runTurn(turnInput()).toList()
            // The zero-barrier promise under streaming: all three reads
            // START although each call arrived only after a pause.
            assertEquals(3, started.get())
            assertEquals(3, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
        }
    }

    @Test
    fun `a schema violation streamed mid-generation never executes`() = runTest {
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                emit(StreamEvent.ToolCall(EngineToolCall("c1", "root_shell", "{}")))
                Thread.sleep(200)
                emit(StreamEvent.Done)
            }
        }
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("never", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(0, executed)
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(!finished.success)
        assertTrue(finished.summary.contains("Tool not available in this session"))
    }

    @Test
    fun `a stream failure after speculative launches drains without history`() = runTest {
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                emit(StreamEvent.ToolCall(EngineToolCall("c1", "file_read", """{"path":"/a.kt"}""")))
                Thread.sleep(150)
                emit(StreamEvent.Failure("connection closed", recoverable = true))
            }
        }
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("discarded", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        // Await-and-discard policy (spec review point): the in-flight
        // tool is NOT cancelled mid-write — it drains to completion —
        // but its result never reaches the model (the assistant round
        // was never completed, so no history is appended).
        assertEquals(1, executed)
        val err = events.last() as AgentEvent.Error
        assertTrue(err.recoverable)
        assertTrue(events.none { it is AgentEvent.TurnFinished })
    }
}
