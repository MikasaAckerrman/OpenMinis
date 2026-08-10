package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunProgressTest {

    @After
    fun tearDown() = AgentRunProgress.clearAll()

    @Test
    fun `active node is the one currently running`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "planner", AgentRole.ORCHESTRATOR, "Orchestrator", null, "m")
        assertEquals("planner", AgentRunProgress.snapshotFor("t1")?.activeNode?.runtimeId)

        AgentRunProgress.nodeSettled("t1", "planner", AgentRunProgress.NodeState.COMPLETED)
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        val snap = AgentRunProgress.snapshotFor("t1")!!
        assertEquals("coder", snap.activeNode?.runtimeId)
        assertEquals(1, snap.completedCount)
        assertTrue(snap.isRunning)
    }

    @Test
    fun `a retried node does not appear twice`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        assertEquals(1, AgentRunProgress.snapshotFor("t1")!!.nodes.size)
    }

    @Test
    fun `skipped node is recorded even though it never started`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeSettled("t1", "coder", AgentRunProgress.NodeState.FAILED)
        AgentRunProgress.nodeSkipped("t1", "reviewer", AgentRole.CODE_CORRECTNESS_REVIEWER, "Reviewer")
        val snap = AgentRunProgress.snapshotFor("t1")!!
        assertEquals(2, snap.nodes.size)
        assertEquals(
            AgentRunProgress.NodeState.SKIPPED,
            snap.nodes.first { it.runtimeId == "reviewer" }.state,
        )
    }

    @Test
    fun `finish settles a node left running so the UI never spins forever`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.finish("t1", "FAILED")
        val snap = AgentRunProgress.snapshotFor("t1")!!
        assertFalse(snap.isRunning)
        assertNull(snap.activeNode)
        assertEquals(AgentRunProgress.NodeState.FAILED, snap.nodes.single().state)
    }

    @Test
    fun `progress never claims more nodes than it has seen`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "a", AgentRole.ORCHESTRATOR, "Orchestrator", null, "m")
        AgentRunProgress.nodeSettled("t1", "a", AgentRunProgress.NodeState.COMPLETED)
        val snap = AgentRunProgress.snapshotFor("t1")!!
        assertTrue(snap.completedCount <= snap.seenCount)
    }

    @Test
    fun `updates for an unknown run are ignored`() {
        AgentRunProgress.nodeStarted("ghost", "x", AgentRole.ORCHESTRATOR, "Orchestrator", null, "m")
        assertNull(AgentRunProgress.snapshotFor("ghost"))
    }

    @Test
    fun `parallel runs stay independent`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.begin("t2", "Full")
        AgentRunProgress.nodeStarted("t1", "planner", AgentRole.ORCHESTRATOR, "Orchestrator", null, "m")
        assertEquals(1, AgentRunProgress.snapshotFor("t1")!!.nodes.size)
        assertEquals(0, AgentRunProgress.snapshotFor("t2")!!.nodes.size)
        AgentRunProgress.clear("t1")
        assertNull(AgentRunProgress.snapshotFor("t1"))
        assertEquals("Full", AgentRunProgress.snapshotFor("t2")!!.graphName)
    }

    @Test
    fun `replica info is preserved for sharded nodes`() {
        AgentRunProgress.begin("t1", "Full")
        AgentRunProgress.nodeStarted("t1", "impl#1", AgentRole.SENIOR_IMPLEMENTER, "Implementer", "1/2", "m")
        assertEquals("1/2", AgentRunProgress.snapshotFor("t1")!!.nodes.single().replicaInfo)
    }

    @Test
    fun `attempt and timeout are surfaced so a retry is not silent`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "planner", AgentRole.ORCHESTRATOR, "Orchestrator", null, "m")
        AgentRunProgress.nodeAttempt("t1", "planner", 2, 3, 360_000L)
        val node = AgentRunProgress.snapshotFor("t1")!!.nodes.single()
        assertEquals(2, node.attempt)
        assertEquals(3, node.maxAttempts)
        assertEquals(360_000L, node.timeoutMs)
    }

    @Test
    fun `a new attempt clears the previous tool`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeTool("t1", "coder", "shell_execute")
        AgentRunProgress.nodeAttempt("t1", "coder", 2, 3, 480_000L)
        assertNull(AgentRunProgress.snapshotFor("t1")!!.nodes.single().tool)
    }

    @Test
    fun `running node reports the tool it is in`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeTool("t1", "coder", "file_write")
        assertEquals("file_write", AgentRunProgress.snapshotFor("t1")!!.nodes.single().tool)
        AgentRunProgress.nodeTool("t1", "coder", null)
        assertNull(AgentRunProgress.snapshotFor("t1")!!.nodes.single().tool)
    }

    @Test
    fun `settling a node drops its tool`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeTool("t1", "coder", "shell_execute")
        AgentRunProgress.nodeSettled("t1", "coder", AgentRunProgress.NodeState.COMPLETED)
        assertNull(AgentRunProgress.snapshotFor("t1")!!.nodes.single().tool)
    }

    @Test
    fun `a settled node ignores late tool events`() {
        // A tool result can land after the node was settled (cancellation race).
        // Reanimating a finished row would be a lie.
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeSettled("t1", "coder", AgentRunProgress.NodeState.FAILED)
        AgentRunProgress.nodeTool("t1", "coder", "shell_execute")
        assertNull(AgentRunProgress.snapshotFor("t1")!!.nodes.single().tool)
    }

    @Test
    fun `finish clears the tool of a node left running`() {
        AgentRunProgress.begin("t1", "Light")
        AgentRunProgress.nodeStarted("t1", "coder", AgentRole.SENIOR_IMPLEMENTER, "Implementer", null, "m")
        AgentRunProgress.nodeTool("t1", "coder", "shell_execute")
        AgentRunProgress.finish("t1", "CANCELLED")
        val node = AgentRunProgress.snapshotFor("t1")!!.nodes.single()
        assertEquals(AgentRunProgress.NodeState.FAILED, node.state)
        assertNull(node.tool)
    }
}
