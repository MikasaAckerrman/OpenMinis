package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-worker-prompt] The standalone flag decides whether a session's prompt
 * REPLACES the 22 000-char general assistant prompt or is appended under it.
 * Getting this wrong is expensive in one direction (5 500 wasted tokens per call)
 * and behaviour-breaking in the other (a normal chat losing its whole prompt), so
 * the flag's lifecycle is worth pinning down.
 */
class AgentSystemPromptStandaloneTest {

    @After
    fun tearDown() = AgentSystemPromptStore.clearAll()

    @Test
    fun `default is additive so existing callers are unchanged`() {
        AgentSystemPromptStore.setPrompt("s1", "role addendum")
        assertFalse(AgentSystemPromptStore.isStandalone("s1"))
    }

    @Test
    fun `worker opts in explicitly`() {
        AgentSystemPromptStore.setPrompt("worker", "minimal worker prompt", standalone = true)
        assertTrue(AgentSystemPromptStore.isStandalone("worker"))
    }

    @Test
    fun `re-registering without the flag downgrades to additive`() {
        // A group session is rewritten per turn by whichever role runs next; a
        // stale standalone flag would silently strip the general prompt from a
        // later, non-worker use of the same session id.
        AgentSystemPromptStore.setPrompt("s", "worker", standalone = true)
        AgentSystemPromptStore.setPrompt("s", "plain addendum")
        assertFalse(AgentSystemPromptStore.isStandalone("s"))
    }

    @Test
    fun `blank prompt clears both prompt and flag`() {
        AgentSystemPromptStore.setPrompt("s", "worker", standalone = true)
        AgentSystemPromptStore.setPrompt("s", "   ")
        assertFalse(AgentSystemPromptStore.isStandalone("s"))
        assertTrue(AgentSystemPromptStore.promptFor("s") == null)
    }

    @Test
    fun `clearPrompt drops the flag so a recycled session id is not standalone`() {
        AgentSystemPromptStore.setPrompt("s", "worker", standalone = true)
        AgentSystemPromptStore.clearPrompt("s")
        assertFalse(AgentSystemPromptStore.isStandalone("s"))
    }

    @Test
    fun `unknown session is never standalone`() {
        assertFalse(AgentSystemPromptStore.isStandalone("never-seen"))
    }

    @Test
    fun `clearAll drops every flag`() {
        AgentSystemPromptStore.setPrompt("a", "w", standalone = true)
        AgentSystemPromptStore.setPrompt("b", "w", standalone = true)
        AgentSystemPromptStore.clearAll()
        assertFalse(AgentSystemPromptStore.isStandalone("a"))
        assertFalse(AgentSystemPromptStore.isStandalone("b"))
    }
}
