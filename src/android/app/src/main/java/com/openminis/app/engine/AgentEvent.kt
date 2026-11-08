package com.openminis.app.engine

/**
 * [T-engine-events] Everything the agent loop can emit during a turn.
 *
 * The UI renders these; the engine never talks to the UI directly. The
 * loop is a cold stream of AgentEvent — consumers collect, cancellation of
 * the collection is the user's STOP.
 */
sealed interface AgentEvent {

    /** A piece of assistant text as it streams in. */
    data class TextDelta(val text: String) : AgentEvent

    /**
     * Progressive reasoning/thinking display — the model's chain of
     * thought as it streams (rendered as the thinking indicator's
     * content in production). Preserved end-to-end through the engine
     * path so a production swap keeps the reasoning UI feature.
     */
    data class ThinkingDelta(val text: String) : AgentEvent

    /**
     * [T-reasoning-degeneracy] The model's thinking turned into a token
     * loop and the engine stopped it.
     *
     * The fragment is the piece of text that repeated. It is kept out of
     * the reasoning content on purpose: the content the provider gets
     * back must be what the provider sent.
     */
    data class ThinkingDegenerate(val fragment: String) : AgentEvent

    /** A tool call's argument JSON is arriving fragment-by-fragment. */
    data class ToolInputDelta(val callId: String, val fragment: String) : AgentEvent

    /** The model announced a tool call (id+name; args still streaming). */
    data class ToolUseStarted(val callId: String, val toolName: String) : AgentEvent

    /** A tool call is about to run (post-preflight, post-gate, post-hooks). */
    data class ToolCallStarted(
        val callId: String,
        val toolName: String,
        val title: String,
    ) : AgentEvent

    /** A tool call completed. [summary] is a one-line outcome for the UI. */
    data class ToolCallFinished(
        val callId: String,
        val toolName: String,
        val success: Boolean,
        val summary: String,
    ) : AgentEvent

    /**
     * [B29-engine-usage] Token accounting for one gateway round. The loop
     * previously DROPPED this ("accounting is the caller's seam" — but the
     * raw StreamEvents end inside the loop, so the seam went nowhere) and
     * engine-driven turns persisted token_usage = NULL: cost accounting
     * blind, context-pressure gates blind, dynamicMaxTokens guessing.
     * Emitted once per gateway round; the driver accumulates the turn.
     */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent

    /** PLAN mode: the agent finished exploring and presents its plan. */
    data class PlanProposed(val markdown: String) : AgentEvent

    /** The turn ended normally. [reason]: "stop", "length", "tool_limit"... */
    data class TurnFinished(val reason: String) : AgentEvent

    /** The turn failed. [recoverable] — a retry may succeed (network class). */
    data class Error(val message: String, val recoverable: Boolean) : AgentEvent

    /**
     * [B42-network-retry] A transient transport failure killed this round's
     * stream attempt and the loop is re-opening the gateway stream after
     * [waitMs] ([NetworkRetryPolicy] ladder). [attempt] is 1-based.
     *
     * The consumer reverts the failed attempt's streamed content (the
     * reducer rolls back to its round baseline) and may surface a
     * transient status — nothing in the failed attempt is durable, because
     * the retry precondition guarantees no tool was dispatched in it.
     */
    data class RoundRetrying(
        val attempt: Int,
        val maxRetries: Int,
        val waitMs: Long,
        val cause: String,
    ) : AgentEvent {
        /** The one-line status the UI shows during the backoff wait. */
        val statusText: String
            get() = "Network glitch, retrying in ${waitMs / 1000}s (attempt $attempt/$maxRetries)..."
    }
}
