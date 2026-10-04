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

    /** PLAN mode: the agent finished exploring and presents its plan. */
    data class PlanProposed(val markdown: String) : AgentEvent

    /** The turn ended normally. [reason]: "stop", "length", "tool_limit"... */
    data class TurnFinished(val reason: String) : AgentEvent

    /** The turn failed. [recoverable] — a retry may succeed (network class). */
    data class Error(val message: String, val recoverable: Boolean) : AgentEvent
}
