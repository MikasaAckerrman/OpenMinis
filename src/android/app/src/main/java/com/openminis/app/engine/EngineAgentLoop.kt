package com.openminis.app.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers

/**
 * [T-m7-agent-loop] The engine-side agent loop (M7 slice 4) — the round
 * structure the M1 contract promised: user text in, a cold stream of
 * [AgentEvent] out, tools executed between model rounds until the model
 * stops calling tools or a limit fires.
 *
 * Per round:
 *  1. stream one completion through [ModelGateway] (TextDelta/ToolCall);
 *  2. for the finished tool_calls: preflight ([ToolPreflight] via the
 *     registry's definitions), then execute through [ToolContext.dispatch]
 *     — the [ToolScheduler] plans execution waves (conflict-aware:
 *     disjoint reads parallelize under a maxConcurrent semaphore;
 *     same-resource read/write and write/write serialize in model
 *     order; GLOBAL tools run alone); a blocked preflight becomes a
 *     FAILED tool result carrying the model-facing message (the
 *     one-round self-correction contract);
 *  3. append the assistant round + tool results to the WORKING history
 *     (TurnInput.history stays an immutable snapshot — the loop owns its
 *     working copy) and loop.
 *
 * Termination: no tool calls → TurnFinished("stop"); [LoopLimits.maxRounds]
 * → TurnFinished("round_limit"); gateway Failure → Error(recoverable).
 * Cancellation of the collection is the user's STOP.
 *
 * PURE ENGINE: no persistence, no UI, no org.json — platform seams M8+
 * wire into production (the ChatViewModel loop stays the production
 * driver until then; this class runs headless against a fake gateway).
 */
class EngineAgentLoop(
    private val gateway: ModelGateway,
    private val registry: ToolRegistry,
    /**
     * [T-tool-scheduler] Resource-keyed wave planner. The DEFAULT is the
     * conservative no-declaration mode (undeclared -> GLOBAL lock, every
     * batch serializes) — the production wiring injects the real key
     * extractor so disjoint reads/writes actually parallelize while
     * same-resource writes serialize. Replaces the all-or-nothing
     * batchPlanner gate (which also ran on empty keys in this loop: two
     * file_edit calls on the SAME file could run concurrently — the P2C
     * write race, engine edition).
     */
    private val toolScheduler: ToolScheduler = ToolScheduler(),
    /**
     * JVM-pure seam for engine observability: the app injects an AppLogger
     * adapter, tests inject STDERR/NONE. The [Scheduler] wave plan rides it.
     */
    private val onEngineEvent: (message: String) -> Unit = {},
    /** Tool execution seam: name + args → output/success. Default: refuse. */
    private val toolExecutor: suspend (toolName: String, argsJson: String) -> ToolOutcome =
        { name, _ ->
            throw IllegalStateException("no executor wired for tool '$name'")
        },
) : AgentLoop {

    /** The engine view of one tool outcome (output text + success). */
    data class ToolOutcome(
        val output: String,
        val success: Boolean,
    )

    override fun runTurn(input: TurnInput): Flow<AgentEvent> = flow {
        // [T-m10-user-turn-seed] The input's userText is THIS turn's
        // prompt: the prior conversation arrives in history, the new
        // message must lead the request — round 1 without it would send
        // the model a history that never contains what the user asked
        // (caught by the reasoning-echo oracle test).
        val workingHistory = input.history.toMutableList()
        if (input.userText.isNotEmpty()) {
            workingHistory.add(EngineMessage(EngineRole.USER, text = input.userText))
        }
        val schema = registry.schemaFor(
            DefaultPermissionGate(
                mode = input.mode,
                writePolicy = ReadOnlyShellPolicy,
            ),
        )
        var round = 0
        while (true) {
            round++
            // [T-m12-cancel-semantics] Cooperative cancellation at the round
            // boundary: a STOP tapped during tool execution must not start
            // another model round. CancellationException propagates out of
            // runTurn to the driver's cleanup path.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (round > input.limits.maxRounds) {
                emit(AgentEvent.TurnFinished("round_limit"))
                return@flow
            }
            val turnText = StringBuilder()
            // [T-m10-reasoning-echo] Reasoning deltas accumulate per round;
            // the assistant history message carries them as
            // reasoningContent (the DeepSeek thinking-history invariant).
            val turnReasoning = StringBuilder()
            val pendingCalls = mutableListOf<EngineToolCall>()
            var failure: AgentEvent.Error? = null

            // [T-engine-maxtokens-parity] production budget from the driver
            // (default 8192 for headless callers) — see TurnInput.maxTokens.
            gateway.stream(workingHistory.toList(), schema, maxTokens = input.maxTokens)
                .collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            if (failure == null) {
                                turnText.append(event.text)
                                emit(AgentEvent.TextDelta(event.text))
                            }
                        }
                        // Progressive surfaces pass straight through: the
                        // consumer renders reasoning/arg-echo UI exactly as
                        // the ViewModel loop does today — the engine never
                        // drops a production feature silently.
                        is StreamEvent.ReasoningDelta -> {
                            if (failure == null) {
                                turnReasoning.append(event.text)
                                emit(AgentEvent.ThinkingDelta(event.text))
                            }
                        }
                        is StreamEvent.ToolUseStarted ->
                            if (failure == null) {
                                emit(AgentEvent.ToolUseStarted(event.callId, event.toolName))
                            }
                        is StreamEvent.ToolInputDelta ->
                            if (failure == null) emit(AgentEvent.ToolInputDelta(event.callId, event.fragment))
                        is StreamEvent.ToolCall -> pendingCalls.add(event.call)
                        is StreamEvent.Usage -> Unit // accounting is the caller's seam
                        is StreamEvent.Failure -> failure = AgentEvent.Error(
                            event.message, event.recoverable,
                        )
                        StreamEvent.Done -> Unit
                    }
                }
            failure?.let {
                emit(it)
                return@flow
            }

            if (pendingCalls.isEmpty()) {
                emit(AgentEvent.TurnFinished("stop"))
                return@flow
            }

            // "About to run" signals — the UI renders running blocks from
            // these (preflight/gate/hooks rejections still surface as
            // ToolCallFinished(success=false) below).
            for (call in pendingCalls) {
                emit(
                    AgentEvent.ToolCallStarted(
                        callId = call.id,
                        toolName = call.name,
                        title = call.name,
                    ),
                )
            }
            val results = executeBatch(pendingCalls, schema, input)
            results.forEach { (call, outcome) ->
                emit(
                    AgentEvent.ToolCallFinished(
                        callId = call.id,
                        toolName = call.name,
                        success = outcome.success,
                        summary = outcome.output.take(200),
                    ),
                )
            }
            // Canonical tool-protocol order: the assistant round first,
            // its tool results after — appended together at the tail.
            workingHistory.add(
                EngineMessage(
                    role = EngineRole.ASSISTANT,
                    text = turnText.toString(),
                    toolCalls = pendingCalls,
                    reasoningContent = turnReasoning.toString().ifEmpty { null },
                ),
            )
            for ((call, outcome) in results) {
                workingHistory.add(
                    EngineMessage(
                        role = EngineRole.TOOL,
                        text = outcome.output,
                        toolCallId = call.id,
                        toolName = call.name,
                    ),
                )
            }
            // [T-engine-queue-interrupt] iOS d14174d3 parity — the engine
            // edition. User report (vc114/115): on the legacy path a queued
            // message starts working the MOMENT a tool closes (the loop
            // injects it at the post-tool boundary), while the engine path
            // ran its whole plan to convergence and only then drained the
            // queue — "ты их видишь но игнорируешь" (they see it but ignore
            // it). Semantics here: at this same boundary (tool results
            // appended, next model round not yet requested) poll the
            // driver; if the user queued a message, append it to the
            // working history so the NEXT model call sees it and responds
            // to it — same stream slot, no new turn ceremony. The assistant
            // round above closes the tool protocol, so appending a USER
            // message here is canonical order (no consecutive-user fold:
            // the tail is TOOL, not USER).
            input.onPendingUserMessage?.let { poll ->
                val queuedText = poll()
                if (queuedText != null && queuedText.isNotEmpty()) {
                    workingHistory.add(EngineMessage(EngineRole.USER, text = queuedText))
                    onEngineEvent("[QueueInterrupt] round=$round user message injected mid-turn (${queuedText.length}ch)")
                }
            }
        }
    }

    /**
     * Execute one batch: preflight every call, then run through the
     * executor — concurrently when the planner allows, else in model
     * order. Results keep the model's original order regardless.
     */
    private suspend fun executeBatch(
        calls: List<EngineToolCall>,
        schema: List<com.openminis.app.data.model.AgentToolDefinition>,
        input: TurnInput,
    ): List<Pair<EngineToolCall, ToolOutcome>> {
        val preflighted = calls.map { call ->
            val def = schema.firstOrNull { it.name == call.name }
            if (def != null) {
                val reason = preflightArgs(call, def)
                if (reason != null) return@map call to ToolOutcome(reason, success = false)
            }
            call to null
        }
        val toRun = preflighted.mapNotNull { (call, outcome) ->
            if (outcome == null) call else null
        }
        // [T-tool-scheduler] Wave execution: conflict-aware parallelism.
        // Reads on disjoint resources run concurrently; same-resource
        // read/write and write/write serialize in model order; GLOBAL
        // tools run alone. Waves execute sequentially; inside a wave a
        // maxConcurrent semaphore caps the fan-out. Each call executes
        // exactly once; results land in the map by call id.
        val sched = toRun.map { ToolScheduler.SchedCall(it.id, it.name, it.argsJson) }
        val waves = toolScheduler.plan(sched)
        // [T-scheduler-observability] The parallelism contract, visible:
        // N proposed calls -> M waves; a wave with >1 call RUNS those calls
        // concurrently (verify: overlapping [Engine] tool timestamps).
        // Serial waves are the conflict matrix doing its job.
        onEngineEvent(
            "[Scheduler] ${toRun.size} call(s) -> ${waves.size} wave(s): " +
                waves.mapIndexed { i, w ->
                    "w${i + 1}=[${w.calls.joinToString(",") { it.name }}]" +
                        if (w.calls.size > 1) "(parallel)" else ""
                }.joinToString(" "),
        )
        val ran: Map<String, ToolOutcome> = if (toRun.size > 1) {
            val results = mutableMapOf<String, ToolOutcome>()
            for (wave in waves) {
                if (wave.calls.size == 1) {
                    val call = toRun.first { it.id == wave.calls.first().id }
                    results[call.id] = runExecutor(call)
                } else {
                    val semaphore = Semaphore(toolScheduler.cap())
                    coroutineScope {
                        wave.calls.map { schedCall ->
                            val call = toRun.first { it.id == schedCall.id }
                            async(Dispatchers.Default) {
                                semaphore.withPermit { runExecutor(call) to schedCall.id }
                            }
                        }.forEach { deferred ->
                            val (outcome, id) = deferred.await()
                            results[id] = outcome
                        }
                    }
                }
            }
            results
        } else {
            toRun.associate { call -> call.id to runExecutor(call) }
        }
        // Original order, preflight-blocked entries in place.
        return preflighted.map { (call, outcome) ->
            call to (outcome ?: ran[call.id] ?: ToolOutcome(
                "Error: tool did not run.", success = false,
            ))
        }
    }

    private suspend fun runExecutor(call: EngineToolCall): ToolOutcome =
        runCatching { toolExecutor(call.name, call.argsJson) }
            .map { it }
            .getOrElse { err ->
                // [T-m12-cancel-semantics] A cancelled coroutine MUST die,
                // not report a tool failure: swallowing CancellationException
                // here would eat the user's STOP and let the loop run on.
                if (err is kotlinx.coroutines.CancellationException) throw err
                ToolOutcome(
                    "Error: tool execution failed: ${err.message ?: err.javaClass.simpleName}",
                    success = false,
                )
            }

    /**
     * Preflight with engine-normalized values. Args are the model's JSON:
     * normalization here is a minimal structural parse (field presence,
     * string/number/boolean/array/object/null) — the full schema check
     * runs platform-side in M8 wiring; headless it keeps the contract.
     */
    private fun preflightArgs(
        call: EngineToolCall,
        def: com.openminis.app.data.model.AgentToolDefinition,
    ): String? {
        val normalized = mutableMapOf<String, PreflightValue>()
        val trimmed = call.argsJson.trim()
        if (!trimmed.startsWith("{")) return null // not an object — platform parse will reject
        // Minimal field split: "key":<value> at the top level. Good enough
        // for presence/type checks; the authoritative parse is platform-side.
        // Number alternative accepts scientific notation (1e3 is legal
        // JSON the org.json platform parser reads as a Double) so the
        // structural presence check matches the platform's notion of
        // "field present".
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|\\[|\\{|(?:true|false)|(?:-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)|null)")
        for (m in regex.findAll(trimmed)) {
            val key = m.groupValues[1]
            // FIRST match wins: regex cannot respect JSON nesting, so a
            // nested object like {"path":[{"path":"/a"}]} yields both the
            // OUTER "path" (Array) and an inner one (Text) — taking the
            // last would let the inner value MASK a wrong-typed outer
            // field and bypass the type check. The outer level is the
            // schema-relevant one; inner spill is ignored.
            if (key in normalized) continue
            val value = m.groupValues[2]
            normalized[key] = when {
                value.startsWith("\"") -> PreflightValue.Text(m.groupValues[3])
                value == "true" || value == "false" -> PreflightValue.Bool(value == "true")
                value == "null" -> PreflightValue.Null
                value == "[" -> PreflightValue.Array
                value == "{" -> PreflightValue.Object
                value.contains(".") || value.contains("e") || value.contains("E") -> {
                    // Class-parity with the org.json adapter: a whole-valued
                    // decimal (3.0, 1e3) is an IntNum there — the two
                    // parsers must agree or preflight verdicts diverge
                    // between headless and platform paths.
                    val d = value.toDouble()
                    if (d.isFinite() && d == Math.floor(d)) {
                        PreflightValue.IntNum(d.toLong())
                    } else {
                        PreflightValue.RealNum(d)
                    }
                }
                else -> PreflightValue.IntNum(value.toLong())
            }
        }
        return ToolPreflight.validate(call.name, normalized, def)
    }
}
