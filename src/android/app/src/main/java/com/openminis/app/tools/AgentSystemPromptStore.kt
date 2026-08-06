package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-agent-graph-role-prompt] Per-session system-prompt addendum for
 * multi-agent graph nodes, held in memory only.
 *
 * Sibling of [AgentToolPolicyStore], and for the same reason: a graph node's
 * role contract is meaningful only for the lifetime of the run, so persisting
 * it would mean a Room migration on the file that holds every user chat, for
 * state that is worthless after the process dies.
 *
 * Why this exists at all: [com.openminis.app.offload.AgentGraphRunner] built a
 * full role prompt — scope contract, tool allowlist rationale, and the
 * mandatory HANDOFF block — and then dropped it on the floor. Nodes ran with
 * the ordinary Minis assistant prompt, so they answered the task directly like
 * a normal chat instead of emitting a handoff, and every run died on the first
 * node with PARSE_FAILURE. The prompt had no route into the session at all;
 * this store is that route.
 *
 * Contract:
 *  - The runner registers a prompt for a node's session BEFORE each send.
 *    Group sessions are shared by several roles, so the entry is overwritten
 *    per turn rather than written once at session creation.
 *  - `ChatViewModel.buildSystemPrompt()` reads it on every request, so a
 *    prompt registered after the ViewModel was constructed still applies.
 *  - Sessions with no entry (i.e. every normal chat) are untouched:
 *    [promptFor] returns null and the caller adds nothing.
 */
object AgentSystemPromptStore {

    private val prompts = ConcurrentHashMap<String, String>()

    /**
     * Attach [systemPrompt] to [sessionId]. A blank prompt clears the entry
     * instead of appending an empty section.
     */
    fun setPrompt(sessionId: String, systemPrompt: String) {
        if (systemPrompt.isBlank()) {
            prompts.remove(sessionId)
        } else {
            prompts[sessionId] = systemPrompt
        }
    }

    /** Role prompt for [sessionId], or null for an ordinary chat. */
    fun promptFor(sessionId: String): String? = prompts[sessionId]

    /** Drop the entry — called when a graph run ends or its session is deleted. */
    fun clearPrompt(sessionId: String) {
        prompts.remove(sessionId)
    }

    /** Drop every entry. Used by tests and on a full graph-engine reset. */
    fun clearAll() {
        prompts.clear()
    }
}
