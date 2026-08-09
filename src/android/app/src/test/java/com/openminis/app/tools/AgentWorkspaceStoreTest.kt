package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWorkspaceStoreTest {
    @Test
    fun `workspace override is isolated and cleared per worker`() {
        AgentWorkspaceStore.clearAll()
        AgentWorkspaceStore.set("planner", "/host/run-1")
        AgentWorkspaceStore.set("coder", "/host/run-1")
        AgentWorkspaceStore.set("other-run", "/host/run-2")
        assertEquals("/host/run-1", AgentWorkspaceStore.get("planner"))
        assertEquals("/host/run-1", AgentWorkspaceStore.get("coder"))
        assertEquals("/host/run-2", AgentWorkspaceStore.get("other-run"))
        AgentWorkspaceStore.clear("planner")
        assertNull(AgentWorkspaceStore.get("planner"))
        assertEquals("/host/run-1", AgentWorkspaceStore.get("coder"))
        AgentWorkspaceStore.clearAll()
    }
}
