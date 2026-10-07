package com.openminis.app.engine

import kotlinx.coroutines.flow.Flow

/**
 * [T-agent-loop-contract] The agent loop contract.
 *
 * One turn = user text in, a stream of [AgentEvent] out, tools executed
 * between model rounds until the model stops calling tools or a limit
 * fires. The implementation migrates out of ChatViewModel in M7; this is
 * the surface it will satisfy, defined NOW so every M2–M6 component
 * (gates, hooks, registry, todo) is built against the final shape.
 */

data class LoopLimits(
    /** Hard ceiling of model rounds per user turn. */
    val maxRounds: Int = 200,
    /** Parallel tool-call batch ceiling within one round. */
    val maxConcurrentTools: Int = 10,
)

data class TurnInput(
    val sessionId: String,
    val userText: String,
    val history: List<EngineMessage>,
    val mode: PermissionMode,
    // [T-engine-maxtokens-parity] Legacy computes max_tokens per turn
    // (dynamicMaxTokens: 128K ceiling, model-cap clamp, context-pressure
    // fallback, thinking-budget tiers). The engine loop previously
    // hardcoded 8192: thinking models (Qwen/DashScope) carve the reasoning
    // budget OUT OF max_tokens, so 8192 truncated mid-thought with a silent
    // "stop". The driver passes the production-computed budget; 8192 stays
    // the default for headless/graph callers.
    val maxTokens: Int = 8192,
    val limits: LoopLimits = LoopLimits(),
)

/**
 * [T-engine-blind-turn-guard] Thrown when a converted engine history
 * contains ZERO user messages: the pipeline lost the user's input
 * (converter regression, diet bug, history rebuild gap) and sending the
 * request would make the model answer blind — pattern-continuing the
 * last assistant turn instead of the user. Call sites catch this and
 * degrade the turn onto the legacy loop, which builds the request
 * directly from the same agentHistory.
 */
class EngineHistoryContractException(message: String) :
    IllegalStateException(message)

interface AgentLoop {
    /**
     * Run one turn. Cold stream: collection starts the turn, cancellation
     * is the user's STOP. Terminates after TurnFinished or Error.
     */
    fun runTurn(input: TurnInput): Flow<AgentEvent>
}
