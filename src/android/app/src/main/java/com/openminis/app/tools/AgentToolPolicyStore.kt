package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-agent-graph] Per-session tool allowlist, held in memory only.
 *
 * Why not a DB column: the allowlist is only meaningful for the lifetime of a
 * graph run. Persisting it would mean a Room migration on `minis.db` — the file
 * that holds every user chat — for state that is worthless after the process
 * dies. A process-scoped map is the honest storage for process-scoped data.
 *
 * Contract:
 *  - [AgentSessionManager] registers a policy right after it creates the
 *    session for a graph node, BEFORE the first prompt is sent.
 *  - `ChatViewModel.agentTools` is a `get()` that re-reads on every use, so a
 *    policy registered after the ViewModel was constructed still applies.
 *  - Sessions with no entry here (i.e. every normal chat) get the full tool
 *    set — [policyFor] returns null and the caller treats null as "no limit".
 */
object AgentToolPolicyStore {

    private val policies = ConcurrentHashMap<String, List<String>>()

    /**
     * Restrict [sessionId] to [allowedTools]. An empty list clears the
     * restriction rather than banning everything — an empty `allowedTools` in
     * a graph node means "unspecified", not "no tools", and silently muting a
     * node would be a confusing failure mode.
     */
    fun setPolicy(sessionId: String, allowedTools: List<String>) {
        if (allowedTools.isEmpty()) {
            policies.remove(sessionId)
        } else {
            policies[sessionId] = allowedTools.toList()
        }
    }

    /** Allowlist for [sessionId], or null when unrestricted. */
    fun policyFor(sessionId: String): List<String>? = policies[sessionId]

    /** Drop the policy — call when a graph run ends or its session is deleted. */
    fun clearPolicy(sessionId: String) {
        policies.remove(sessionId)
    }

    /** Drop every policy. Used by tests and on a full graph-engine reset. */
    fun clearAll() {
        policies.clear()
    }
}
