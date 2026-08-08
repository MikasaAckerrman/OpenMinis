package com.openminis.app.offload

import com.openminis.app.offload.AgentGraphScheduler.AgentGraphSpec
import com.openminis.app.offload.AgentGraphScheduler.Edge
import com.openminis.app.offload.AgentGraphScheduler.NodeStatus
import com.openminis.app.offload.AgentGraphScheduler.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dry-run of the graph scheduler with a stub model. Proves node sequencing
 * without a build/provider/quota — the one link a device run was the only way
 * to check before.
 */
class AgentGraphSchedulerTest {

    /** builtin_light: planner -> coder -> reviewer, exit = reviewer. */
    private val light = AgentGraphSpec(
        entryNodeId = "planner",
        exitNodeIds = listOf("reviewer"),
        edges = listOf(Edge("planner", "coder"), Edge("coder", "reviewer")),
    )

    @Test
    fun `light graph runs all three nodes in order when each completes`() {
        // This is the exact scenario that hung on-device before 13671b4:
        // planner COMPLETE, then the loop must reap it and queue coder.
        val r = AgentGraphScheduler.run(light) { Outcome.COMPLETE }
        assertEquals(listOf("planner", "coder", "reviewer"), r.dispatchOrder)
        assertEquals(NodeStatus.COMPLETED, r.statusByNode["reviewer"])
        assertFalse(r.deadlock)
    }

    @Test
    fun `a blocked planner stops the chain without running coder`() {
        val r = AgentGraphScheduler.run(light) { id ->
            if (id == "planner") Outcome.BLOCKED else Outcome.COMPLETE
        }
        assertEquals(listOf("planner"), r.dispatchOrder)
        assertEquals(NodeStatus.BLOCKED, r.statusByNode["planner"])
        // coder must be recorded SKIPPED (terminal) so the run settles, not hang.
        assertEquals(NodeStatus.SKIPPED, r.statusByNode["coder"])
        assertFalse(r.deadlock)
    }

    @Test
    fun `a failed coder leaves reviewer skipped and the run settles`() {
        val r = AgentGraphScheduler.run(light) { id ->
            if (id == "coder") Outcome.FAILED else Outcome.COMPLETE
        }
        assertEquals(listOf("planner", "coder"), r.dispatchOrder)
        assertEquals(NodeStatus.SKIPPED, r.statusByNode["reviewer"])
        assertFalse(r.deadlock)
    }

    /** Fan-in: two predecessors into one node; it waits for both. */
    @Test
    fun `fan-in node waits for every predecessor before running`() {
        val g = AgentGraphSpec(
            entryNodeId = "plan",
            exitNodeIds = listOf("gate"),
            edges = listOf(
                Edge("plan", "a"), Edge("plan", "b"),
                Edge("a", "gate"), Edge("b", "gate"),
            ),
        )
        val r = AgentGraphScheduler.run(g) { Outcome.COMPLETE }
        // gate must appear exactly once and after both a and b.
        assertEquals(1, r.dispatchOrder.count { it == "gate" })
        assertTrue(r.dispatchOrder.indexOf("gate") > r.dispatchOrder.indexOf("a"))
        assertTrue(r.dispatchOrder.indexOf("gate") > r.dispatchOrder.indexOf("b"))
        assertEquals(NodeStatus.COMPLETED, r.statusByNode["gate"])
    }

    @Test
    fun `fan-in still runs when one branch failed but another completed`() {
        val g = AgentGraphSpec(
            entryNodeId = "plan",
            exitNodeIds = listOf("gate"),
            edges = listOf(
                Edge("plan", "a"), Edge("plan", "b"),
                Edge("a", "gate"), Edge("b", "gate"),
            ),
        )
        val r = AgentGraphScheduler.run(g) { id ->
            if (id == "b") Outcome.FAILED else Outcome.COMPLETE
        }
        // At least one predecessor COMPLETED, so gate runs.
        assertTrue("gate" in r.dispatchOrder)
        assertEquals(NodeStatus.COMPLETED, r.statusByNode["gate"])
    }

    /** Replicas: two implementers fan into a reviewer. */
    @Test
    fun `replicas each run once and their successor waits for all of them`() {
        val g = AgentGraphSpec(
            entryNodeId = "plan",
            exitNodeIds = listOf("review"),
            edges = listOf(Edge("plan", "impl"), Edge("impl", "review")),
            replicaIds = mapOf("impl" to listOf("impl#1", "impl#2")),
        )
        val r = AgentGraphScheduler.run(g) { Outcome.COMPLETE }
        assertEquals(1, r.dispatchOrder.count { it == "impl#1" })
        assertEquals(1, r.dispatchOrder.count { it == "impl#2" })
        assertTrue(r.dispatchOrder.indexOf("review") > r.dispatchOrder.indexOf("impl#1"))
        assertTrue(r.dispatchOrder.indexOf("review") > r.dispatchOrder.indexOf("impl#2"))
        assertFalse(r.deadlock)
    }

    @Test
    fun `full-shaped graph completes end to end`() {
        // orchestrator -> discovery -> architect -> implementer -> correctness -> gatekeeper
        val g = AgentGraphSpec(
            entryNodeId = "orchestrator",
            exitNodeIds = listOf("gatekeeper"),
            edges = listOf(
                Edge("orchestrator", "discovery"),
                Edge("discovery", "architect"),
                Edge("architect", "implementer"),
                Edge("implementer", "correctness"),
                Edge("correctness", "gatekeeper"),
            ),
        )
        val r = AgentGraphScheduler.run(g) { Outcome.COMPLETE }
        assertEquals(
            listOf("orchestrator", "discovery", "architect", "implementer", "correctness", "gatekeeper"),
            r.dispatchOrder,
        )
        assertFalse(r.deadlock)
    }

    @Test
    fun `a blocked node mid-chain cascades skip to the exit and settles`() {
        // The builtin_full-shaped regression: a blocked middle node must not
        // leave everything downstream PENDING forever (a false deadlock). Every
        // later node ends SKIPPED and the run settles.
        val g = AgentGraphSpec(
            entryNodeId = "orchestrator",
            exitNodeIds = listOf("gatekeeper"),
            edges = listOf(
                Edge("orchestrator", "discovery"),
                Edge("discovery", "architect"),
                Edge("architect", "implementer"),
                Edge("implementer", "correctness"),
                Edge("correctness", "gatekeeper"),
            ),
        )
        val r = AgentGraphScheduler.run(g) { id ->
            if (id == "architect") Outcome.BLOCKED else Outcome.COMPLETE
        }
        assertFalse("must settle, not deadlock", r.deadlock)
        assertEquals(NodeStatus.BLOCKED, r.statusByNode["architect"])
        assertEquals(NodeStatus.SKIPPED, r.statusByNode["implementer"])
        assertEquals(NodeStatus.SKIPPED, r.statusByNode["correctness"])
        assertEquals(NodeStatus.SKIPPED, r.statusByNode["gatekeeper"])
        // Nothing past the block should have been dispatched.
        assertEquals(listOf("orchestrator", "discovery", "architect"), r.dispatchOrder)
    }

    @Test
    fun `no node runs twice even with diamond edges`() {
        val g = AgentGraphSpec(
            entryNodeId = "a",
            exitNodeIds = listOf("d"),
            edges = listOf(
                Edge("a", "b"), Edge("a", "c"),
                Edge("b", "d"), Edge("c", "d"),
            ),
        )
        val r = AgentGraphScheduler.run(g) { Outcome.COMPLETE }
        assertEquals(r.dispatchOrder.distinct(), r.dispatchOrder)
        assertEquals(NodeStatus.COMPLETED, r.statusByNode["d"])
    }
}
