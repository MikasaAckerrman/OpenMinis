package com.openminis.app.offload

import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.TraceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentGraphRunner.failureReason] is the F3 fix: a FAILED graph run must
 * reach the caller with a human-readable cause in GraphRunResult.error
 * instead of the bare "produced no output.". The cause is the last
 * non-soft trace event of every non-COMPLETED node, exit nodes first.
 */
class AgentGraphRunnerFailureReasonTest {

    private fun node(id: String): AgentNode = AgentNode(
        id = id,
        role = AgentRole.SENIOR_IMPLEMENTER,
        systemPrompt = "test prompt",
    )

    private fun state(
        nodes: List<AgentNode>,
        statusById: Map<String, AgentGraphRunner.NodeStatus>,
        events: List<TraceEvent>,
    ): AgentGraphRunner.GraphState {
        val graph = AgentGraph(
            name = "test-graph",
            nodes = nodes,
            entryNodeId = nodes.first().id,
            exitNodeIds = listOf(nodes.last().id),
        )
        val full = AgentGraphRunner.GraphState(
            graph = graph,
            taskId = "task-1",
            artifactDir = "artifacts",
            artifactHostDir = null,
            input = "query",
        )
        full.nodeStatus.putAll(statusById)
        events.forEach { full.trace.add(it) }
        return full
    }

    private fun event(nid: String, action: String, details: String, ts: Long = 0L) = TraceEvent(
        timestamp = ts,
        nodeId = nid,
        role = AgentRole.SENIOR_IMPLEMENTER,
        action = action,
        details = details,
    )

    @Test
    fun `empty success state yields no error`() {
        val n = node("n1")
        val s = state(listOf(n), mapOf("n1" to AgentGraphRunner.NodeStatus.COMPLETED), emptyList())
        assertNull(AgentGraphRunner.failureReason(s))
    }

    @Test
    fun `last terminal event beats soft noise`() {
        val n = node("n1")
        val s = state(
            listOf(n),
            mapOf("n1" to AgentGraphRunner.NodeStatus.FAILED),
            listOf(
                event("n1", "ATTEMPT", "1/3: Transient error: HTTP 500", ts = 10L),
                event("n1", "RETRY", "wait", ts = 20L),
                event("n1", "FAILED", "After 3 attempts: server_error", ts = 30L),
                event("n1", "CHECKPOINT-SKIP", "not needed", ts = 40L),
            ),
        )
        val reason = AgentGraphRunner.failureReason(s)
        assertTrue(reason != null)
        assertTrue("terminal FAILED must win: got [$reason]", reason!!.contains("FAILED"))
        assertTrue("cause text must be kept: got [$reason]", reason.contains("server_error"))
    }

    @Test
    fun `no trace falls back to node status`() {
        val n = node("n1")
        val s = state(listOf(n), mapOf("n1" to AgentGraphRunner.NodeStatus.BLOCKED), emptyList())
        val reason = AgentGraphRunner.failureReason(s)!!
        assertTrue("status is the reason: [$reason]", reason.contains("BLOCKED"))
    }

    @Test
    fun `exit node line comes first`() {
        val mid = node("mid")
        val exit = node("exit")
        val s = state(
            listOf(mid, exit),
            mapOf(
                "mid" to AgentGraphRunner.NodeStatus.FAILED,
                "exit" to AgentGraphRunner.NodeStatus.FAILED,
            ),
            listOf(
                event("mid", "FAILED", "mid failed first", ts = 10L),
                event("exit", "FAILED", "exit failed after", ts = 20L),
            ),
        )
        val reason = AgentGraphRunner.failureReason(s)!!
        val midIdx = reason.indexOf("mid failed first")
        val exitIdx = reason.indexOf("exit failed after")
        assertTrue("both causes present: [$reason]", midIdx >= 0 && exitIdx >= 0)
        assertTrue("exit node reported first: [$reason]", exitIdx < midIdx)
    }
}
