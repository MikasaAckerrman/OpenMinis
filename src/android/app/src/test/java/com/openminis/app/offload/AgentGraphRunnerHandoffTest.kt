package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.Handoff
import com.openminis.app.data.model.HandoffStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentGraphRunner.pickFinalHandoff] decides which handoff becomes the answer a
 * user reads, so the tie-breaking rules are worth pinning: with several exit
 * nodes, a BLOCKED branch must not shadow a finished one.
 */
class AgentGraphRunnerHandoffTest {

    private fun handoff(status: HandoffStatus, next: String) = Handoff(
        from = AgentRole.SENIOR_IMPLEMENTER,
        to = AgentRole.FINAL_GATEKEEPER,
        taskId = "t1",
        status = status,
        nextAction = next,
    )

    @Test
    fun `no exit handoffs means no answer to report`() {
        assertNull(AgentGraphRunner.pickFinalHandoff(emptyList()))
    }

    @Test
    fun `the single handoff is used whatever its status`() {
        val blocked = handoff(HandoffStatus.BLOCKED, "needs the API key")
        assertEquals(blocked, AgentGraphRunner.pickFinalHandoff(listOf(blocked)))
    }

    @Test
    fun `a complete handoff wins over an earlier blocked one`() {
        val blocked = handoff(HandoffStatus.BLOCKED, "blocked branch")
        val done = handoff(HandoffStatus.COMPLETE, "done branch")
        assertEquals(done, AgentGraphRunner.pickFinalHandoff(listOf(blocked, done)))
    }

    @Test
    fun `the first complete handoff wins over later ones`() {
        val first = handoff(HandoffStatus.COMPLETE, "first")
        val second = handoff(HandoffStatus.COMPLETE, "second")
        assertEquals(first, AgentGraphRunner.pickFinalHandoff(listOf(first, second)))
    }

    @Test
    fun `needs-clarification is reported when nothing completed`() {
        val ask = handoff(HandoffStatus.NEEDS_CLARIFICATION, "which database?")
        val blocked = handoff(HandoffStatus.BLOCKED, "blocked")
        assertEquals(ask, AgentGraphRunner.pickFinalHandoff(listOf(ask, blocked)))
    }

    @Test
    fun `rendered handoff carries the fields a reader needs`() {
        val text = HandoffValidator.buildHandoff(handoff(HandoffStatus.COMPLETE, "ship it"))
        assertTrue(text.contains("STATUS: COMPLETE"))
        assertTrue(text.contains("ship it"))
    }
}
