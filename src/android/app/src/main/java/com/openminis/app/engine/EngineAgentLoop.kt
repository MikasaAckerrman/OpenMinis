package com.openminis.app.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.Dispatchers
import com.openminis.app.logging.AppLogger

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

    override fun runTurn(input: TurnInput): Flow<AgentEvent> = channelFlow {
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
                return@channelFlow
            }
            val turnText = StringBuilder()
            // [T-m10-reasoning-echo] Reasoning deltas accumulate per round;
            // the assistant history message carries them as
            // reasoningContent (the DeepSeek thinking-history invariant).
            val turnReasoning = StringBuilder()
            // [T-reasoning-degeneracy] Set once per round when the model's
            // thinking turned into a token loop. After that the reasoning
            // is not accumulated and not shown: it must not reach the
            // stored reasoning_content, and it must not go back to the
            // provider as part of the history.
            var reasoningDegenerate = false
            // [T-reasoning-degeneracy] The detector needs the recent text
            // only, and copying the whole buffer on every delta would be
            // quadratic: a long reasoning would copy itself over and over
            // while it streams. This keeps the tail and nothing else.
            val reasoningTail = StringBuilder()
            // [T-reasoning-degeneracy] The watch keeps the evidence of the whole
            // stream, so a loop in the middle of the reasoning is still provable
            // after the tail moved on. [B42-network-retry] var: a round retry
            // starts a FRESH stream — evidence from the failed attempt must
            // not taint the loop detection of the retried one.
            var reasoningWatch = ReasoningLoopWatch()
            // [PARALLEL_TOOL_ENGINE_SPEC Опора 1] Streaming speculative
            // dispatch: a tool call whose args JSON has CLOSED (the
            // provider emits StreamEvent.ToolCall exactly then) launches
            // into execution IMMEDIATELY — while the model is still
            // generating the rest of its response. The dispatcher owns
            // the calls in model order (the history round is built from
            // this list) and the two concurrency gates.
            // [B43-per-tool-streaming] The settle seam sends straight into
            // the channel: a Finished event leaves the moment its tool
            // lands, while slower siblings keep running.
            val dispatcher = BatchDispatcher(schema) { call, outcome ->
                send(
                    AgentEvent.ToolCallFinished(
                        callId = call.id,
                        toolName = call.name,
                        success = outcome.success,
                        summary = outcome.output.take(200),
                    ),
                )
            }
            val pendingCalls = dispatcher.callsInOrder
            var failure: AgentEvent.Error? = null

            // [B42-network-retry] The attempt loop: a transient transport
            // failure re-opens the gateway stream after the policy backoff
            // INSTEAD of tearing the turn down to the manual-Resume path.
            // The gate ([NetworkRetryPolicy.shouldRetry]) refuses when any
            // tool was dispatched in this round — a retried request may
            // re-emit those calls and speculative dispatch would execute
            // them twice — and after MAX_RETRIES the failure surfaces
            // exactly as it did before this loop existed. A retry is
            // INVISIBLE to the round counter: it does not burn a round of
            // maxRounds, and the driver learns of it only through the
            // RoundRetrying event (revert + transient status).
            var streamFailure: StreamEvent.Failure? = null
            var attempt = 0
            attemptLoop@ while (true) {
                streamFailure = null
                // [T-engine-maxtokens-parity] production budget from the driver
                // (default 8192 for headless callers) — see TurnInput.maxTokens.
                // coroutineScope joins the speculative coroutines on exit: a
                // failed stream drains its in-flight tools before the error is
                // surfaced (await-and-discard — a mid-write cancel would risk
                // a torn file), and a user STOP cancels them through the same
                // structured scope ([T-m12-cancel-semantics] preserved).
                coroutineScope {
                    val scope = this
                    gateway.stream(workingHistory.toList(), schema, maxTokens = input.maxTokens)
                        .collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> {
                                    if (streamFailure == null) {
                                        // [T-output-arrows] Collapse the arrow-run degeneration
                                        // before it reaches the user and the saved message.
                                        val clean = OutputSanitizer.sanitizeArrows(event.text)
                                        turnText.append(clean)
                                        emit(AgentEvent.TextDelta(clean))
                                    }
                                }
                            // Progressive surfaces pass straight through: the
                            // consumer renders reasoning/arg-echo UI exactly as
                            // the ViewModel loop does today — the engine never
                            // drops a production feature silently.
                            is StreamEvent.ReasoningDelta -> {
                                if (streamFailure == null) {
                                    // [T-reasoning-degeneracy] The text is
                                    // checked before it is accumulated and
                                    // before it reaches the screen. A loop is
                                    // dropped here, so it is stored nowhere and
                                    // the provider never sees it again.
                                    reasoningTail.append(event.text)
                                    if (reasoningTail.length > ReasoningDegeneracy.TAIL_CHARS) {
                                        reasoningTail.delete(
                                            0,
                                            reasoningTail.length - ReasoningDegeneracy.TAIL_CHARS,
                                        )
                                    }
                                    val loop = reasoningWatch.accept(event.text)
                                    if (loop == null) {
                                        turnReasoning.append(event.text)
                                        emit(AgentEvent.ThinkingDelta(event.text))
                                    } else if (!reasoningDegenerate) {
                                        reasoningDegenerate = true
                                        AppLogger.warning(
                                            "Reasoning",
                                            "degenerate reasoning dropped: fragment=" +
                                                "'${loop.fragment}' x${loop.occurrences} " +
                                                "coverage=${loop.coverage} at ${turnReasoning.length} chars",
                                        )
                                        // The UI is told first, so it can say why
                                        // the turn stops instead of showing an
                                        // empty answer.
                                        // The repeated piece is the proof; for a
                                        // sign flood the proof is the sign
                                        // itself, which would tell the user
                                        // nothing. The excerpt is the text
                                        // itself, so the warning can show what
                                        // the model actually wrote.
                                        emit(
                                            AgentEvent.ThinkingDegenerate(
                                                reasoningTail.toString()
                                                    .takeLast(ReasoningDegeneracy.EXCERPT_CHARS),
                                            ),
                                        )
                                        // Stop the generation. CancellationException
                                        // is the project's own cancellation
                                        // mechanism: it propagates out of runTurn
                                        // to the driver's cleanup path, the
                                        // provider stream is unwound (the OkHttp
                                        // call is cancelled), and the driver
                                        // rethrows it - a cancelled turn, not a
                                        // failed one.
                                        //
                                        // The reasoning that was good and already
                                        // streamed stays visible in the live
                                        // block; this round's message is not
                                        // written, exactly like a turn the user
                                        // stopped by hand.
                                        throw kotlinx.coroutines.CancellationException(
                                            "degenerate reasoning: '${loop.fragment}'",
                                        )
                                    }
                                }
                            }
                            is StreamEvent.ToolUseStarted ->
                                if (streamFailure == null) {
                                    emit(AgentEvent.ToolUseStarted(event.callId, event.toolName))
                                }
                            is StreamEvent.ToolInputDelta ->
                                if (streamFailure == null) emit(AgentEvent.ToolInputDelta(event.callId, event.fragment))
                            is StreamEvent.ToolCall -> if (streamFailure == null) {
                                // [Опора 1] The call's args JSON is CLOSED: launch
                                // it into the gates NOW, while the model keeps
                                // generating. The "about to run" signal moves to
                                // dispatch time too — the UI card appears while
                                // the response is still streaming (preflight/
                                // schema rejections still surface as
                                // ToolCallFinished(success=false) below).
                                emit(
                                    AgentEvent.ToolCallStarted(
                                        callId = event.call.id,
                                        toolName = event.call.name,
                                        title = event.call.name,
                                    ),
                                )
                                dispatcher.dispatch(scope, event.call)
                            }
                            is StreamEvent.Usage ->
                                // [B29-engine-usage] "Accounting is the caller's
                                // seam" — but the raw StreamEvents END here, so the
                                // seam went nowhere and engine turns persisted
                                // token_usage = NULL. Bridge it out instead.
                                emit(AgentEvent.Usage(event.inputTokens, event.outputTokens))
                            is StreamEvent.Failure ->
                                // [B42-network-retry] The gateway completes the
                                // flow right after this event, so the collect
                                // ends here; the retry decision runs below on
                                // the post-scope state.
                                streamFailure = event
                            StreamEvent.Done -> Unit
                        }
                    }
                }
                val sf = streamFailure
                if (sf == null) break@attemptLoop
                if (NetworkRetryPolicy.shouldRetry(
                        transient = sf.transient,
                        attempt = attempt,
                        toolsDispatched = dispatcher.callsInOrder.isNotEmpty(),
                    )
                ) {
                    attempt++
                    val waitMs = NetworkRetryPolicy.waitMsForAttempt(attempt)
                    onEngineEvent(
                        "[Retry] transient stream failure (round=$round, attempt=$attempt/" +
                            "${NetworkRetryPolicy.MAX_RETRIES}, wait=${waitMs}ms): " +
                            "${sf.message?.take(160)}",
                    )
                    emit(
                        AgentEvent.RoundRetrying(
                            attempt = attempt,
                            maxRetries = NetworkRetryPolicy.MAX_RETRIES,
                            waitMs = waitMs,
                            cause = sf.message,
                        ),
                    )
                    // Void the failed attempt's accumulators — the retried
                    // stream re-produces them from an empty round. The UI
                    // side mirrors this through the reducer's round baseline.
                    turnText.setLength(0)
                    turnReasoning.setLength(0)
                    reasoningTail.setLength(0)
                    reasoningDegenerate = false
                    reasoningWatch = ReasoningLoopWatch()
                    kotlinx.coroutines.delay(waitMs)
                    continue@attemptLoop
                }
                failure = AgentEvent.Error(sf.message, sf.recoverable)
                break@attemptLoop
            }
            failure?.let {
                emit(it)
                return@channelFlow
            }

            if (pendingCalls.isEmpty()) {
                emit(AgentEvent.TurnFinished("stop"))
                return@channelFlow
            }

            // [Scheduler] observability rides the same seam as before —
            // but the numbers describe the ONLINE build now: "immediate"
            // means launched while the model was still generating.
            val planned = dispatcher.dagSnapshot()
            val immediate = planned.count { it.dependsOn.isEmpty() }
            onEngineEvent(
                "[Scheduler] ${pendingCalls.size} call(s) reactive (streamed): " +
                    "$immediate immediate, ${planned.size - immediate} chained on resource deps",
            )

            // [B43-per-tool-streaming] The Finished events ALREADY streamed:
            // each call settled into `send` the moment it completed (the
            // dispatcher's watchers live inside the coroutineScope above,
            // which joins them before we get here). The old awaitAll +
            // forEach burst held every card open until the SLOWEST tool of
            // the batch drained, then closed them all in one tick — the
            // vc155/156 live report ("тулы заканчивают в одно время").
            // What remains here is the MODEL-ORDER result list for the
            // history round; every deferred has settled, so the awaits
            // return instantly.
            val results = dispatcher.resultsInModelOrder()
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
     * [PARALLEL_TOOL_ENGINE_SPEC Опора 1] The streaming batch dispatcher:
     * calls arrive ONE AT A TIME from the provider stream (each when its
     * args JSON has closed) and launch into the gates immediately — the
     * DAG is built ONLINE through [ToolScheduler.ReactiveDagBuilder], so
     * a call that just closed depends only on the calls dispatched
     * before it, exactly like planReactive would have decided for the
     * finished batch. Results keep the model's original order.
     *
     * Same safety rules as the old executeBatch, moved verbatim:
     * the schema is the allowlist (an unknown name is a failed outcome,
     * never an execution), preflight runs per call at dispatch time,
     * a waiting call holds no gate slot (the semaphore is acquired
     * AFTER the dependency waits).
     */
    private inner class BatchDispatcher(
        private val schema: List<com.openminis.app.data.model.AgentToolDefinition>,
        /**
         * [B43-per-tool-streaming] The settle seam: invoked the moment a
         * call reaches its terminal outcome — blocked calls at dispatch
         * time, executed calls at completion. The loop wires it to the
         * channelFlow channel (a plain `flow {}` forbids emission from
         * child coroutines, and THAT restriction is exactly why Finished
         * events used to burst: the loop could only emit after awaiting
         * the whole batch).
         */
        private val settle: suspend (EngineToolCall, ToolOutcome) -> Unit,
    ) {
        // [P1-io-concurrency] Two gates, one per tool class. The CPU gate
        // keeps its subagent-sized cap (a spawn is a live model stream +
        // a socket + a rate-limit share); disk/network tools get the I/O
        // cap — a 10-way file_write batch to 10 distinct paths must not
        // wait behind spawns. Resource conflicts stay the scheduler's
        // job (the DAG deps); the gates only bound how many calls may
        // run AT ONCE.
        private val cpuGate = Semaphore(toolScheduler.cap())
        private val ioGate = Semaphore(toolScheduler.ioCap())
        private val dag = toolScheduler.ReactiveDagBuilder()
        private val deferreds =
            java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ToolOutcome>>()
        private val blocked = java.util.concurrent.ConcurrentHashMap<String, ToolOutcome>()

        /** The calls in model order — the history round is built from
         *  this list, preflight-blocked entries included. */
        val callsInOrder = mutableListOf<EngineToolCall>()

        suspend fun dispatch(scope: kotlinx.coroutines.CoroutineScope, call: EngineToolCall) {
            callsInOrder.add(call)
            val def = schema.firstOrNull { it.name == call.name }
            if (def == null) {
                // A tool that is not in this turn's schema has no permission
                // attached to it, so it cannot be preflighted - and it must not
                // run either. The schema is the allowlist: dropping the call
                // here is what keeps a subagent restricted to file_read from
                // reaching file_write through a name the model invented or
                // replayed from an earlier turn.
                val outcome = ToolOutcome(
                    "Tool not available in this session: ${call.name}",
                    success = false,
                )
                blocked[call.id] = outcome
                settle(call, outcome)
                return
            }
            val reason = preflightArgs(call, def)
            if (reason != null) {
                val outcome = ToolOutcome(reason, success = false)
                blocked[call.id] = outcome
                settle(call, outcome)
                return
            }
            val node = dag.add(ToolScheduler.SchedCall(call.id, call.name, call.argsJson))
            val gate = if (ToolConcurrencyClass.isIo(call.name)) ioGate else cpuGate
            val deferred = scope.async(Dispatchers.Default) {
                node.dependsOn.forEach { depId -> deferreds.getValue(depId).await() }
                gate.withPermit { runExecutor(call) }
            }
            deferreds[call.id] = deferred
            // [B43-per-tool-streaming] The completion watcher: fire the
            // settle seam the instant THIS tool lands — the watcher is a
            // child of the same scope, so the attempt's coroutineScope
            // join (below) still guarantees every Finished event is in
            // the channel before the round's history is built. A
            // cancelled deferred (stream failure retry, user STOP) makes
            // the watcher's await() throw CancellationException — no
            // Finished event, exactly the old cancel semantics.
            deferred.invokeOnCompletion {
                scope.launch {
                    runCatching { settle(call, deferred.await()) }
                }
            }
        }

        /**
         * Model order, preflight-blocked entries in place. Every call has
         * already settled by the time the loop calls this (the scope join
         * below the stream drained all watchers), so the awaits return
         * instantly — this builds the HISTORY round, it no longer gates
         * the UI.
         */
        suspend fun resultsInModelOrder(): List<Pair<EngineToolCall, ToolOutcome>> =
            callsInOrder.map { call ->
                call to (
                    blocked[call.id]
                        ?: deferreds[call.id]?.await()
                        ?: ToolOutcome("Error: tool did not run.", success = false)
                )
            }

        /** The DAG as built so far — observability and tests. */
        fun dagSnapshot(): List<ToolScheduler.ReactiveNode> = dag.build()
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
