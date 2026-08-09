package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimePolicyStoreTest {
    @Test
    fun `cap is isolated by session and can be cleared`() {
        AgentRuntimePolicyStore.clearAll()
        AgentRuntimePolicyStore.setMaxOutputTokens("worker-a", 16_384)
        AgentRuntimePolicyStore.setMaxOutputTokens("worker-b", 4_096)
        assertEquals(16_384, AgentRuntimePolicyStore.maxOutputTokensFor("worker-a"))
        assertEquals(4_096, AgentRuntimePolicyStore.maxOutputTokensFor("worker-b"))
        AgentRuntimePolicyStore.clear("worker-a")
        assertNull(AgentRuntimePolicyStore.maxOutputTokensFor("worker-a"))
        assertEquals(4_096, AgentRuntimePolicyStore.maxOutputTokensFor("worker-b"))
        AgentRuntimePolicyStore.clearAll()
    }

    @Test
    fun `non-positive cap means no worker policy`() {
        AgentRuntimePolicyStore.clearAll()
        AgentRuntimePolicyStore.setMaxOutputTokens("worker", 16_384)
        AgentRuntimePolicyStore.setMaxOutputTokens("worker", 0)
        assertNull(AgentRuntimePolicyStore.maxOutputTokensFor("worker"))
    }
}
