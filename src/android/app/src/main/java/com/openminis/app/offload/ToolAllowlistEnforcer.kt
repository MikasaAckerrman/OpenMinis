package com.openminis.app.offload

import com.openminis.app.data.model.AgentNode
import com.openminis.app.tools.AgentTools

/**
 * [T-agent-graph] Per-agent tool allowlist helpers.
 *
 * The actual ENFORCEMENT happens one layer down: [AgentTools.makeAgentTools]
 * filters the tool schema by the allowlist stored in
 * [com.openminis.app.tools.AgentToolPolicyStore], so a tool the node may not
 * use never reaches the model. This object only validates the config and
 * renders it for the system prompt.
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

    /** Whether [tool] is permitted for [node]. Empty allowlist = everything. */
    fun isAllowed(node: AgentNode, tool: String): Boolean {
        if (node.allowedTools.isEmpty()) return true
        val target = AgentTools.canonicalToolName(tool)
        return node.allowedTools.any { raw ->
            if (raw.trim().lowercase() == "memory") {
                target == "memory_write" || target == "memory_get"
            } else {
                AgentTools.canonicalToolName(raw) == target
            }
        }
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