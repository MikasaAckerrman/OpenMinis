package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * [B16-stage2b] Per-session MCP server allowlist, held in memory only
 * (mirroring the design of [AgentToolPolicyStore]).
 *
 * Contract:
 *  - When a custom agent specifies `mcpServers: ...`, [AgentSessionManager]
 *    or [com.openminis.app.offload.AgentGraphRunner] registers the policy for that session.
 *  - [McpCallTool.execute] enforces that only allowed servers can be called
 *    or queried via `mcp(action=...)`.
 *  - Sessions with no entry here (normal chat) get full access to all
 *    enabled MCP servers configured in `servers.json`.
 */
object AgentMcpPolicyStore {

    private val policies = ConcurrentHashMap<String, List<String>>()

    /**
     * Restrict [sessionId] to [allowedServers]. An empty list or null clears
     * the restriction. Server names are stored trimmed and lowercased.
     */
    fun setPolicy(sessionId: String, allowedServers: List<String>?) {
        if (allowedServers.isNullOrEmpty()) {
            policies.remove(sessionId)
        } else {
            policies[sessionId] = allowedServers.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        }
    }

    /** Allowlist of server names for [sessionId], or null when unrestricted. */
    fun policyFor(sessionId: String): List<String>? = policies[sessionId]

    /** Drop the policy when a session is finished or deleted. */
    fun clearPolicy(sessionId: String) {
        policies.remove(sessionId)
    }

    /** Drop every policy. Used by tests and on full engine reset. */
    fun clearAll() {
        policies.clear()
    }
}
