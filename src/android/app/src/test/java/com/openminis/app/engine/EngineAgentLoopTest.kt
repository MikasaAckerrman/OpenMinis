package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var seenMaxTokens: Int = -1
            private set
        override fun stream(
            messages: List<EngineMessage>,
            tools: List<AgentToolDefinition>,
            maxTokens: Int,
        ) = flow {
            seenMaxTokens = maxTokens
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

    /** file_write + shell_execute + file_read, for the barrier tests. */
    private fun ioRegistry(): ToolRegistry = ToolRegistry().apply {
        register(object : EngineTool {
            override val name = "file_write"
            override val mutation = MutationKind.WRITE
            override fun definition() = AgentToolDefinition(
                name = "file_write",
                description = "Write",
                parameters = mapOf("path" to AgentToolParam(type = "string", description = "p")),
                required = listOf("path"),
            )
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("written", true)
        })
        register(object : EngineTool {
            override val name = "shell_execute"
            override val mutation = MutationKind.EXECUTE
            override fun definition() = AgentToolDefinition(
                name = "shell_execute",
                description = "Shell",
                parameters = emptyMap(),
                required = emptyList(),
            )
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("shell ok", true)
        })
        register(object : EngineTool {
            override val name = "file_read"
            override val mutation = MutationKind.READ
            override fun definition() = fileReadDef
            override suspend fun execute(argsJson: String, ctx: ToolContext) =
                com.openminis.app.tools.ToolExecutionResult("ok", true)
        })
    }

    @Test
    fun `driver max_tokens budget reaches the gateway not the 8192 default`() = runTest {
        // [T-engine-maxtokens-parity] The loop used to hardcode 8192:
        // thinking models carve their reasoning budget out of max_tokens
        // and truncated mid-thought. The production driver computes the
        // budget (dynamicMaxTokens: ceiling/clamp/context pressure) — the
        // loop must forward it untouched.
        val gw = ScriptedGateway(
            listOf(listOf(StreamEvent.TextDelta("ok"), StreamEvent.Done)),
        )
        EngineAgentLoop(gw, registry()).runTurn(
            turnInput().copy(maxTokens = 32768),
        ).toList()
        assertEquals(32768, gw.seenMaxTokens)
    }

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
    fun `progressive reasoning and arg deltas flow through the loop`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ReasoningDelta("let me think "),
                    StreamEvent.ReasoningDelta("harder"),
                    StreamEvent.ToolUseStarted("c1", "file_read"),
                    StreamEvent.ToolInputDelta("c1", "{\"path\":"),
                    StreamEvent.ToolInputDelta("c1", "\"/a.kt\"}"),
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("body", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        val think = events.filterIsInstance<AgentEvent.ThinkingDelta>()
        assertEquals(listOf("let me think ", "harder"), think.map { it.text })
        val started = events.filterIsInstance<AgentEvent.ToolUseStarted>()
        assertEquals(listOf("c1"), started.map { it.callId })
        val argDeltas = events.filterIsInstance<AgentEvent.ToolInputDelta>()
        assertEquals(2, argDeltas.size)
        // fragments preserved verbatim, order preserved
        assertEquals("{\"path\":", argDeltas[0].fragment)
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
        // [T-tool-scheduler] Production-shaped scheduler: disjoint fs read
        // keys -> one wave -> the two executors MUST overlap.
        val sched = ToolScheduler(resourceKeysOf = { _, args ->
            val p = runCatching { org.json.JSONObject(args).optString("path", "") }.getOrDefault("")
            if (p.isBlank()) null else setOf("fs:$p")
        })
        val loop = EngineAgentLoop(gw, registry(), toolScheduler = sched) { _, _ ->
            // both must overlap: signal entry, wait for the partner.
            // Generous barrier: on a slow CI runner the second async may
            // take seconds to reach the pool — the test asserts OVERLAP,
            // so a timeout short enough to expire first would flake.
            if (start.incrementAndGet() == 1) {
                var waited = 0
                while (start.get() == 1 && waited < 4000) {
                    Thread.sleep(5); waited += 5
                }
            }
            EngineAgentLoop.ToolOutcome("x", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(2, start.get())
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
    }

    @Test
    fun `gateway usage bridges out as AgentEvent Usage`() = runTest {
        // [B29-engine-usage] The loop used to swallow StreamEvent.Usage
        // ("accounting is the caller's seam" — the seam went nowhere):
        // engine turns persisted token_usage = NULL.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.TextDelta("x"),
                    StreamEvent.Usage(120, 34),
                    StreamEvent.Done,
                ),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        val usage = events.filterIsInstance<AgentEvent.Usage>().single()
        assertEquals(120, usage.inputTokens)
        assertEquals(34, usage.outputTokens)
    }

    @Test
    fun `shell_execute stays hermetic between parallel file ops`() = runTest {
        // [B29-engine-shell-isolation] planReactive: an UNDECLARED call
        // (shell_execute — no resource keys) is a two-sided GLOBAL barrier.
        // The mixed batch [file_write(a), shell, file_read(b)] must execute
        // strictly fw -> shell -> fr: nothing runs BESIDE the shell and
        // nothing jumps over it, even though the two file ops are mutually
        // independent (disjoint fs keys).
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_write", "{\"path\":\"/a.txt\"}")),
                    StreamEvent.ToolCall(EngineToolCall("c2", "shell_execute", "{}")),
                    StreamEvent.ToolCall(EngineToolCall("c3", "file_read", "{\"path\":\"/b.txt\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sched = ToolScheduler(resourceKeysOf = { name, args ->
            val p = runCatching { org.json.JSONObject(args).optString("path", "") }.getOrDefault("")
            if (name == "shell_execute" || p.isBlank()) null else setOf("fs:$p")
        })
        val loop = EngineAgentLoop(gw, ioRegistry(), toolScheduler = sched) { name, _ ->
            log.add("start:$name")
            Thread.sleep(120) // widen the window so an illegal overlap cannot hide
            log.add("end:$name")
            EngineAgentLoop.ToolOutcome("ok:$name", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
        assertEquals(
            listOf(
                "start:file_write", "end:file_write",
                "start:shell_execute", "end:shell_execute",
                "start:file_read", "end:file_read",
            ),
            log,
        )
    }

    @Test
    fun `disjoint file ops still overlap inside one batch`() = runTest {
        // [P1-io-concurrency regression guard] The shell barrier must not
        // re-serialize the world: three I/O tools on DISJOINT paths all
        // START before any of them finishes.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_write", "{\"path\":\"/a.txt\"}")),
                    StreamEvent.ToolCall(EngineToolCall("c2", "file_write", "{\"path\":\"/b.txt\"}")),
                    StreamEvent.ToolCall(EngineToolCall("c3", "file_read", "{\"path\":\"/c.txt\"}")),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val sched = ToolScheduler(resourceKeysOf = { name, args ->
            val p = runCatching { org.json.JSONObject(args).optString("path", "") }.getOrDefault("")
            if (name == "shell_execute" || p.isBlank()) null else setOf("fs:$p")
        })
        val loop = EngineAgentLoop(gw, ioRegistry(), toolScheduler = sched) { name, _ ->
            started.incrementAndGet()
            // Barrier: every executor waits for the other two to have
            // STARTED — generous real-time patience for slow CI runners
            // (same pattern as the parallel-safe test above).
            var waited = 0
            while (started.get() < 3 && waited < 4000) {
                Thread.sleep(5); waited += 5
            }
            EngineAgentLoop.ToolOutcome("ok:$name", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(3, started.get())
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolCallFinished>().size)
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
                    // present but MISSING the required "path"
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"other\":1}")),
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
    fun `nested same-name field cannot mask a wrong-typed outer field`() = runTest {
        // regression: the headless regex parser must take the FIRST
        // (outer) occurrence of a key — an inner "path" inside an array
        // value must not turn a wrong-typed outer field into a string.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(
                        EngineToolCall(
                            "c1", "file_read",
                            "{\"path\":[{\"path\":\"/a.kt\"}]}",
                        ),
                    ),
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
        assertEquals(0, executed) // blocked: outer path is an ARRAY
        val toolEvent = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertTrue(!toolEvent.success)
        assertTrue(toolEvent.summary.contains("must be a string"))
    }

    @Test
    fun `reasoning deltas become the history round's reasoningContent`() = runTest {
        // Round 1 streams reasoning + a tool call; round 2 stops. The
        // gateway's SECOND stream call must receive the round-1 assistant
        // message with reasoningContent assembled from the deltas.
        val seen = mutableListOf<List<EngineMessage>>()
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<com.openminis.app.data.model.AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                seen.add(messages)
                if (seen.size == 1) {
                    emit(StreamEvent.ReasoningDelta("step one. "))
                    emit(StreamEvent.ReasoningDelta("step two."))
                    emit(
                        StreamEvent.ToolCall(
                            EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}"),
                        ),
                    )
                }
                emit(StreamEvent.Done)
            }
        }
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("x", true)
        }
        loop.runTurn(turnInput()).toList()
        // round 2's request history: [user, assistant(reasoning), tool]
        val round2 = seen[1]
        assertEquals(3, round2.size)
        val assistant = round2[1]
        assertEquals(EngineRole.ASSISTANT, assistant.role)
        assertEquals("step one. step two.", assistant.reasoningContent)
    }

    @Test
    fun `empty reasoning stays null not empty-string`() = runTest {
        val seen = mutableListOf<List<EngineMessage>>()
        val gw = object : ModelGateway {
            override val modelId = "fake"
            override fun stream(
                messages: List<EngineMessage>,
                tools: List<com.openminis.app.data.model.AgentToolDefinition>,
                maxTokens: Int,
            ) = flow {
                seen.add(messages)
                if (seen.size == 1) {
                    emit(StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")))
                }
                emit(StreamEvent.Done)
            }
        }
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("x", true)
        }
        loop.runTurn(turnInput()).toList()
        val assistant = seen[1][1]
        org.junit.Assert.assertNull(assistant.reasoningContent)
    }

    @Test
    fun `headless parser whole decimals classify as integers`() = runTest {
        // 3.0 must classify as an integer value exactly as the platform
        // org.json adapter does, or preflight verdicts diverge.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(
                        EngineToolCall("c1", "file_read", "{\"path\": \"/a.kt\", \"offset\": 3.0}"),
                    ),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("read", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        org.junit.Assert.assertTrue(
            "3.0 must pass as integer: ${finished.summary}",
            finished.success && !finished.summary.contains("integer"),
        )
    }

    @Test
    fun `headless parser scientific notation is field present`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(
                        EngineToolCall("c1", "file_read", "{\"path\": \"/a.kt\", \"offset\": 1e3}"),
                    ),
                    StreamEvent.Done,
                ),
                listOf(StreamEvent.Done),
            ),
        )
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("read", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        org.junit.Assert.assertTrue(
            "1e3 must be field-present: ${finished.summary}",
            !finished.summary.contains("missing required parameter"),
        )
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
        val finishedCalls = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertEquals(
            // [B43-race-fix] A settle watcher losing the race to the
            // channel close used to swallow a Finished (a UI card stuck
            // on "Running" forever); joinSettleWatchers made the barrier
            // contractual. One Finished per round, none lost.
            "one Finished per round, none lost; events=$events",
            3,
            finishedCalls.size,
        )
    }

    // [T-m12-cancel-semantics] The user's STOP must kill the turn, not be
    // reported as a tool failure the loop would feed to the model.
    @Test
    fun `executor cancellation kills the turn instead of a failed outcome`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.Done,
                ),
            ),
        )
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            throw kotlinx.coroutines.CancellationException("user stop")
        }
        val events = mutableListOf<AgentEvent>()
        var cancelled = false
        try {
            loop.runTurn(turnInput()).collect { events.add(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation must propagate out of runTurn", cancelled)
        assertEquals(
            "no failed-outcome conversion of the cancel",
            0,
            events.count { it is AgentEvent.ToolCallFinished && !it.success },
        )
    }

    @Test
    fun `terminal failure after partial text is not recoverable`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.TextDelta("half"),
                    StreamEvent.Failure("content policy", recoverable = false),
                ),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        // The partial text must survive as an event before the error.
        assertTrue(events.any { it is AgentEvent.TextDelta })
        val err = events.last() as AgentEvent.Error
        assertFalse(err.recoverable)
    }

    @Test
    fun `parallel batch failure keeps the sibling result`() = runTest {
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
        // [T-tool-scheduler] disjoint fs read keys -> one wave -> the failing
        // sibling must not take the good result down with it.
        val sched = ToolScheduler(resourceKeysOf = { _, args ->
            val p = runCatching { org.json.JSONObject(args).optString("path", "") }.getOrDefault("")
            if (p.isBlank()) null else setOf("fs:$p")
        })
        val loop = EngineAgentLoop(gw, registry(), toolScheduler = sched) { _, argsJson ->
            if (argsJson.contains("/a.kt")) EngineAgentLoop.ToolOutcome("good", true)
            else EngineAgentLoop.ToolOutcome("boom: disk full", false)
        }
        val events = loop.runTurn(turnInput()).toList()
        val finishes = events.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertEquals(2, finishes.size)
        assertTrue(finishes.any { it.success && it.summary == "good" })
        assertTrue(finishes.any { !it.success })
        // The turn still reaches a clean finish (the model sees the failure).
        assertTrue(events.last() is AgentEvent.TurnFinished)
    }

    @Test
    fun `empty stream ends the turn without executor calls`() = runTest {
        val gw = ScriptedGateway(listOf(listOf(StreamEvent.Done)))
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("x", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(0, executed)
        val finished = events.last() as AgentEvent.TurnFinished
        assertEquals("stop", finished.reason)
        // Text rides TextDelta events; the finish is a terminator only.
        assertTrue(events.none { it is AgentEvent.TextDelta })
    }

    @Test
    fun `incomplete tool call is never executed`() = runTest {
        // A stream that announces a tool but never completes it (provider
        // died mid-args). The batch is built from COMPLETED calls only, so
        // the round is effectively empty — the turn ends with a clean stop
        // and the executor never runs. (A mid-STREAM death surfaces as
        // Failure instead — covered by the failure tests above.)
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolUseStarted("c1", "file_read"),
                    StreamEvent.ToolInputDelta("c1", "{\"pa"),
                    StreamEvent.Done,
                ),
            ),
        )
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("x", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(0, executed)
        // The announcement still surfaced for the UI (arg echo), but the
        // incomplete call produced no execution and no fabricated result.
        assertTrue(events.any { it is AgentEvent.ToolUseStarted })
        assertTrue(events.none { it is AgentEvent.ToolCallStarted })
        assertTrue(events.none { it is AgentEvent.ToolCallFinished })
        assertEquals("stop", (events.last() as AgentEvent.TurnFinished).reason)
    }

    @Test
    fun `second-round failure preserves round one tool results`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.Done,
                ),
                listOf(
                    StreamEvent.TextDelta("mid"),
                    StreamEvent.Failure("connection closed", recoverable = true),
                ),
            ),
        )
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            EngineAgentLoop.ToolOutcome("file body", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        // Round one's result is an event BEFORE the failure lands.
        val toolIdx = events.indexOfFirst { it is AgentEvent.ToolCallFinished }
        val errIdx = events.indexOfLast { it is AgentEvent.Error }
        assertTrue(toolIdx in 0 until errIdx)
        assertTrue((events.last() as AgentEvent.Error).recoverable)
    }

    // ── [B42-network-retry] in-round network auto-retry ──────────────

    @Test
    fun `transient stream failure retries within the round`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.TextDelta("part"),
                    StreamEvent.Failure(
                        "request failed: stream idle timeout",
                        recoverable = true,
                        transient = true,
                    ),
                ),
                listOf(StreamEvent.TextDelta("hello"), StreamEvent.Done),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        val retry = events.filterIsInstance<AgentEvent.RoundRetrying>()
        assertEquals(1, retry.size)
        assertEquals(1, retry[0].attempt)
        assertEquals(1_500L, retry[0].waitMs)
        assertTrue(retry[0].statusText.contains("attempt 1/3"))
        // The failed attempt's deltas were emitted (the driver reverts
        // them); the retried attempt completes the round normally.
        assertEquals(
            listOf("part", "hello"),
            events.filterIsInstance<AgentEvent.TextDelta>().map { it.text },
        )
        assertTrue(events.last() is AgentEvent.TurnFinished)
    }

    @Test
    fun `non-transient failure surfaces without a retry`() = runTest {
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.Failure(
                        "[500] provider shape",
                        recoverable = false,
                        transient = false,
                    ),
                ),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        assertEquals(0, events.filterIsInstance<AgentEvent.RoundRetrying>().size)
        assertTrue(events.last() is AgentEvent.Error)
    }

    @Test
    fun `retry is refused once a tool was dispatched`() = runTest {
        // The double-execution invariant: the call already launched into
        // execution (speculative dispatch) — re-running the request would
        // re-emit it and execute it twice. The failure surfaces instead.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.ToolCall(EngineToolCall("c1", "file_read", "{\"path\":\"/a.kt\"}")),
                    StreamEvent.Failure(
                        "stream idle timeout",
                        recoverable = true,
                        transient = true,
                    ),
                ),
            ),
        )
        var executed = 0
        val loop = EngineAgentLoop(gw, registry()) { _, _ ->
            executed++
            EngineAgentLoop.ToolOutcome("body", true)
        }
        val events = loop.runTurn(turnInput()).toList()
        assertEquals(1, executed)
        assertEquals(0, events.filterIsInstance<AgentEvent.RoundRetrying>().size)
        assertTrue(events.last() is AgentEvent.Error)
    }

    @Test
    fun `three retries on the policy ladder then the failure surfaces`() = runTest {
        val failureScript = listOf(
            StreamEvent.Failure(
                "request failed: stream idle timeout",
                recoverable = true,
                transient = true,
            ),
        )
        val gw = ScriptedGateway(
            listOf(failureScript, failureScript, failureScript, failureScript),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(turnInput()).toList()
        val retries = events.filterIsInstance<AgentEvent.RoundRetrying>()
        assertEquals(3, retries.size)
        assertEquals(listOf(1_500L, 3_000L, 6_000L), retries.map { it.waitMs })
        assertEquals(listOf(1, 2, 3), retries.map { it.attempt })
        assertTrue(events.last() is AgentEvent.Error)
    }

    @Test
    fun `retry does not consume a round of maxRounds`() = runTest {
        // A failing-then-succeeding attempt with maxRounds=1: the retried
        // request must still be allowed to complete the single round.
        val gw = ScriptedGateway(
            listOf(
                listOf(
                    StreamEvent.Failure(
                        "connection closed",
                        recoverable = true,
                        transient = true,
                    ),
                ),
                listOf(StreamEvent.TextDelta("ok"), StreamEvent.Done),
            ),
        )
        val events = EngineAgentLoop(gw, registry()).runTurn(
            turnInput().copy(limits = com.openminis.app.engine.LoopLimits(maxRounds = 1)),
        ).toList()
        assertTrue(events.last() is AgentEvent.TurnFinished)
    }

}