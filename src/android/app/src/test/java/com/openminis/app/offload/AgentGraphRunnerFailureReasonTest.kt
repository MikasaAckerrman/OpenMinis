package com.openminis.app.offload

import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.EdgeType
import com.openminis.app.data.model.TraceEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [F3-failure-reason] Pins the reason projection of a non-SUCCESS run into
 * GraphRunResult.error. Scenario mirrored from the network journal of
 * 28.10 20:33:56-20:34:21: a MiniMax HTTP 500 burst killed a node after
 * three attempts, and the cause lived only in state.trace while formatResult
 * printed "produced no output.".
 */
class AgentGraphRunnerFailureReasonTest {

    private val nodeId = "node-exit-1"

    private fun state(
        status: AgentGraphRunner.NodeStatus,
        events: List<TraceEvent>,
    ): AgentGraphRunner.GraphState {
        val node = AgentNode(id = nodeId, role = AgentRole.SENIOR_IMPLEMENTER, systemPrompt = "s")
        val graph = AgentGraph(
            id = "g1",
            name = "test",
            nodes = listOf(node),
            edges = emptyList(),
            entryNodeId = nodeId,
            exitNodeIds = listOf(nodeId),
        )
        val s = AgentGraphRunner.GraphState(
            graph = graph,
            taskId = "t1",
            artifactDir = File("/tmp/agent-artifacts"),
            artifactHostDir = null,
            input = "input",
        )
        s.nodeStatus[nodeId] = status
        events.forEach { s.trace.add(it) }
        return s
    }

    private fun ev(
        action: String,
        details: String,
        role: AgentRole = AgentRole.SENIOR_IMPLEMENTER,
    ) = TraceEvent(
        timestamp = System.currentTimeMillis(),
        nodeId = nodeId,
        role = role,
        action = action,
        details = details,
    )

    @Test
    fun failureReasonProjectsLastTerminalEventOfFailedNode() {
        val s = state(
            AgentGraphRunner.NodeStatus.FAILED,
            listOf(
                ev("ATTEMPT", "1/3"),
                ev("RETRY", "waiting 8000ms"),
                ev("ATTEMPT", "2/3"),
                ev("RETRY", "waiting 16000ms"),
                ev("ATTEMPT", "3/3"),
                ev("FAILED", "Transient error: HTTP 500 server_error"),
            ),
        )
        val reason = AgentGraphRunner.failureReason(s)
        assertNotNull(reason)
        assertTrue("HTTP 500" in reason, "reason must carry the terminal details: $reason")
        assertTrue("ATTEMPT" !in reason, "no soft events in reason: $reason")
    }

    @Test
    fun failureReasonCapsOutputAtTwoThousandChars() {
        val s = state(
            AgentGraphRunner.NodeStatus.FAILED,
            listOf(ev("FAILED", "x".repeat(10_000))),
        )
        val reason = AgentGraphRunner.failureReason(s)
        assertNotNull(reason)
        assertEquals(2_000, reason.length)
    }

    @Test
    fun failureReasonFallsBackToNodeStatusWhenTraceIsSilent() {
        val s = state(AgentGraphRunner.NodeStatus.FAILED, emptyList())
        val reason = AgentGraphRunner.failureReason(s)
        assertNotNull(reason)
        assertTrue("FAILED" in reason, "no trace -> status is the reason: $reason")
    }

    @Test
    fun failureReasonIsNullWhenAllNodesCompleted() {
        val s = state(AgentGraphRunner.NodeStatus.COMPLETED, emptyList())
        assertNull(AgentGraphRunner.failureReason(s))
    }

    @Test
    fun softActionsIncludeCheckpointsAndScope() {
        // CHECKPOINT-SKIP was found in the runner after the initial list;
        // pin it so noise never leaks into a terminal reason again.
        val s = state(
            AgentGraphRunner.NodeStatus.FAILED,
            listOf(
                ev("CHECKPOINT-SKIP", "reason=deep"),
                ev("FAILED", "boom"),
            ),
        )
        val reason = AgentGraphRunner.failureReason(s)
        assertNotNull(reason)
        assertTrue("boom" in reason && "CHECKPOINT-SKIP" !in reason, "got: $reason")
    }
}
