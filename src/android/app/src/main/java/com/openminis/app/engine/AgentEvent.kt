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

    /** PLAN mode: the agent finished exploring and presents its plan. */
    data class PlanProposed(val markdown: String) : AgentEvent

    /** The turn ended normally. [reason]: "stop", "length", "tool_limit"... */
    data class TurnFinished(val reason: String) : AgentEvent

    /** The turn failed. [recoverable] — a retry may succeed (network class). */
    data class Error(val message: String, val recoverable: Boolean) : AgentEvent
}
