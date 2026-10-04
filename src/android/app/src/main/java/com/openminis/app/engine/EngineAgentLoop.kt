package com.openminis.app.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
 *     — batches that pass [ToolBatchPlanner] run concurrently, everything
 *     else sequentially in model order; a blocked preflight becomes a
 *     FAILED tool result carrying the model-facing message (the one-round
 *     self-correction contract);
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
    private val batchPlanner: ToolBatchPlanner = ToolBatchPlanner(),
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
        val workingHistory = input.history.toMutableList()
        val schema = registry.schemaFor(
            DefaultPermissionGate(
                mode = input.mode,
                writePolicy = ReadOnlyShellPolicy,
            ),
        )
        var round = 0
        while (true) {
            round++
            if (round > input.limits.maxRounds) {
                emit(AgentEvent.TurnFinished("round_limit"))
                return@flow
            }
            val turnText = StringBuilder()
            val pendingCalls = mutableListOf<EngineToolCall>()
            var failure: AgentEvent.Error? = null

            gateway.stream(workingHistory.toList(), schema, maxTokens = 8192)
                .collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            if (failure == null) {
                                turnText.append(event.text)
                                emit(AgentEvent.TextDelta(event.text))
                            }
                        }
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
                ),
            )
            for ((call, outcome) in results) {
                workingHistory.add(
                    EngineMessage(
                        role = EngineRole.TOOL,
                        text = outcome.output,
                        toolCallId = call.id,
                    ),
                )
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
        val canParallel = batchPlanner.canParallelize(
            calls.map { ToolBatchPlanner.PendingToolCall(it.id, it.name, it.argsJson) },
        )
        val ran: Map<String, ToolOutcome> = if (canParallel && toRun.size > 1) {
            coroutineScope {
                val deferred = toRun.associate { call ->
                    call.id to async(Dispatchers.Default) {
                        runExecutor(call)
                    }
                }
                deferred.mapValues { it.value.await() }
            }
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
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|\\[|\\{|(?:true|false)|(?:-?\\d+(?:\\.\\d+)?)|null)")
        for (m in regex.findAll(trimmed)) {
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            normalized[key] = when {
                value.startsWith("\"") -> PreflightValue.Text(m.groupValues[3])
                value == "true" || value == "false" -> PreflightValue.Bool(value == "true")
                value == "null" -> PreflightValue.Null
                value == "[" -> PreflightValue.Array
                value == "{" -> PreflightValue.Object
                value.contains(".") -> PreflightValue.RealNum(value.toDouble())
                else -> PreflightValue.IntNum(value.toLong())
            }
        }
        return ToolPreflight.validate(call.name, normalized, def)
    }
}
