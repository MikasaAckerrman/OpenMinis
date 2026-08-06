package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-agent-graph-role-prompt] The store is the only route a graph node's role
 * contract has into the provider request, so its contract is worth pinning.
 *
 * The bug it fixes: AgentGraphRunner built the role prompt (scope contract,
 * tool rationale, mandatory HANDOFF block) and discarded it. Nodes ran as the
 * ordinary Minis assistant, answered in prose, and every run died on the entry
 * node with "Could not parse handoff from response". Two properties matter for
 * that not to regress: a registered prompt is readable by the chat layer, and
 * an unregistered session stays untouched (null, not empty string) so normal
 * chats keep their byte-stable cacheable prompt.
 */
class AgentSystemPromptStoreTest {

    @After
    fun tearDown() = AgentSystemPromptStore.clearAll()

    @Test
    fun `an ordinary chat has no role prompt`() {
        // Must be null rather than "": the caller appends only on non-null, and
        // an empty section would break prompt-prefix caching for every chat.
        assertNull(AgentSystemPromptStore.promptFor("plain-session"))
    }

    @Test
    fun `a registered prompt is readable back`() {
        AgentSystemPromptStore.setPrompt("s1", "You are the ORCHESTRATOR agent")
        assertEquals("You are the ORCHESTRATOR agent", AgentSystemPromptStore.promptFor("s1"))
    }

    @Test
    fun `a later role overwrites an earlier one in a shared session`() {
        // sessionGroup nodes share one session: the next role's contract must
        // replace the previous one, or a reviewer would run under the coder's
        // scope contract and be stopped by the scope guard for its own work.
        AgentSystemPromptStore.setPrompt("group-a", "CODER contract")
        AgentSystemPromptStore.setPrompt("group-a", "REVIEWER contract")
        assertEquals("REVIEWER contract", AgentSystemPromptStore.promptFor("group-a"))
    }

    @Test
    fun `a blank prompt clears rather than registering emptiness`() {
        AgentSystemPromptStore.setPrompt("s2", "something")
        AgentSystemPromptStore.setPrompt("s2", "   ")
        assertNull(AgentSystemPromptStore.promptFor("s2"))
    }

    @Test
    fun `clearing one session leaves the others alone`() {
        AgentSystemPromptStore.setPrompt("a", "A")
        AgentSystemPromptStore.setPrompt("b", "B")
        AgentSystemPromptStore.clearPrompt("a")
        assertNull(AgentSystemPromptStore.promptFor("a"))
        assertEquals("B", AgentSystemPromptStore.promptFor("b"))
    }

    @Test
    fun `sessions are independent so a run does not leak into a neighbour`() {
        AgentSystemPromptStore.setPrompt("node-1", "ORCHESTRATOR")
        assertNull(AgentSystemPromptStore.promptFor("node-2"))
    }

    @Test
    fun `clearAll wipes every entry`() {
        AgentSystemPromptStore.setPrompt("x", "X")
        AgentSystemPromptStore.setPrompt("y", "Y")
        AgentSystemPromptStore.clearAll()
        assertNull(AgentSystemPromptStore.promptFor("x"))
        assertNull(AgentSystemPromptStore.promptFor("y"))
    }

    /**
     * The routing guard in ChatViewModel keys off exactly this call: a non-null
     * prompt means "this session is a graph node mid-turn, do not classify it".
     * Locking the shape here keeps the guard's premise honest — if the store
     * ever returned "" for an unset session, every normal chat would look like
     * a worker and auto-routing would silently stop working.
     */
    @Test
    fun `presence of a prompt is the graph-worker marker`() {
        val session = "worker-1"
        assertNull(AgentSystemPromptStore.promptFor(session))   // normal chat: routes
        AgentSystemPromptStore.setPrompt(session, "ORCHESTRATOR contract")
        assertEquals(true, AgentSystemPromptStore.promptFor(session) != null) // worker: does not route
        AgentSystemPromptStore.clearPrompt(session)
        assertNull(AgentSystemPromptStore.promptFor(session))   // run over: routes again
    }
}
