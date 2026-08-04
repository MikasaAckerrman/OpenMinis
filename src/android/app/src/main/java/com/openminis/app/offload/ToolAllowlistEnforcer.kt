package com.openminis.app.offload

import com.openminis.app.data.model.AgentNode
import com.openminis.app.tools.AgentTools

/**
 * [T-agent-graph] Per-agent tool allowlist: validation and prompt text.
 *
 * The actual ENFORCEMENT lives elsewhere, in two layers:
 *  - [AgentTools.makeAgentTools] filters the schema by the allowlist stored in
 *    [com.openminis.app.tools.AgentToolPolicyStore], so a forbidden tool never
 *    reaches the model;
 *  - ChatViewModel.executeTool refuses a call that slipped through anyway.
 *
 * This object only answers "is this allowlist well-formed" and "how do I tell
 * the agent what it has".
 */
object ToolAllowlistEnforcer {

    /** Canonical tool names available to an agent node. */
    val ALL_TOOLS: Set<String> get() = AgentTools.ALL_TOOL_NAMES

    /**
     * Validate an allowlist from a graph config. Returns the names that do not
     * map onto a real tool, so the caller can reject the graph with a useful
     * message instead of silently granting nothing.
     */
    fun unknownTools(allowedTools: List<String>): List<String> =
        allowedTools.filter { raw ->
            val normalized = raw.trim().lowercase()
            // `memory` is shorthand for both memory halves.
            if (normalized == "memory") return@filter false
            AgentTools.canonicalToolName(raw) !in AgentTools.ALL_TOOL_NAMES
        }

    /** Human-readable allowlist for the node's system prompt. */
    fun formatAllowlist(node: AgentNode): String {
        if (node.allowedTools.isEmpty()) return "All tools available."
        val resolved = node.allowedTools.flatMap { raw ->
            if (raw.trim().lowercase() == "memory") listOf("memory_write", "memory_get")
            else listOf(AgentTools.canonicalToolName(raw))
        }.distinct()
        return "Tools available to you (the schema contains ONLY these): " +
            resolved.joinToString(", ")
    }
}