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
    val limits: LoopLimits = LoopLimits(),
)

interface AgentLoop {
    /**
     * Run one turn. Cold stream: collection starts the turn, cancellation
     * is the user's STOP. Terminates after TurnFinished or Error.
     */
    fun runTurn(input: TurnInput): Flow<AgentEvent>
}
