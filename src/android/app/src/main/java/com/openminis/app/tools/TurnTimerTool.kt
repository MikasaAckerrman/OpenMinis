package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [T-turn-timer] The agent arms its own work budget: "работай ровно 1 час".
 * The deadline survives turns, is visible on every tool result, and forces a
 * final summary (done / not done / what remains) when it expires.
 */
object TurnTimerTool {
    const val NAME = "turn_timer"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Arm, check or clear the TURN TIMER — your work budget for this " +
            "session, e.g. when the user says 'работай 1 час' / 'work for N minutes'. " +
            "While armed, every tool result shows the remaining time; when it expires " +
            "you get a stop instruction and must write the final summary: " +
            "DONE / NOT DONE / WHAT REMAINS — no new work after expiry. " +
            "action=set arms it (minutes, 1..1440); action=status shows what's left; " +
            "action=clear disarms it (also happens automatically after the wrap-up turn).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary. Use the user's language."),
            "action" to AgentToolParam("string", "set | status | clear", enumValues = listOf("set", "status", "clear")),
            "minutes" to AgentToolParam(
                "string",
                "For action=set: the budget in minutes, 1..1440 (e.g. \"60\" for one hour).",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "minutes"),
    )
}
