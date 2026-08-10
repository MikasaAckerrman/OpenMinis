package com.openminis.app.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentToolBudgetTest {

    @Test
    fun `calls inside the budget are allowed`() {
        for (used in 0 until 5) {
            assertTrue(
                "call ${used + 1} of 5 should be allowed",
                AgentToolBudget.check(used, 5, "Orchestrator", "a plan").allowed,
            )
        }
    }

    @Test
    fun `the call past the budget is refused`() {
        val v = AgentToolBudget.check(5, 5, "Orchestrator", "a plan")
        assertFalse(v.allowed)
        assertTrue(v.message.isNotBlank())
    }

    @Test
    fun `refusal names the role, the numbers, and the owed artifact`() {
        val v = AgentToolBudget.check(12, 12, "Orchestrator", "a plan: what to change")
        assertTrue(v.message.contains("Orchestrator"))
        assertTrue(v.message.contains("12 of 12"))
        assertTrue(v.message.contains("a plan: what to change"))
    }

    @Test
    fun `refusal offers the blocked handoff as the escape hatch`() {
        // Without this the model's only options are "call another tool" (refused)
        // or invent an answer. Naming BLOCKED keeps the run honest.
        val v = AgentToolBudget.check(3, 3, "Implementer", "the code change")
        assertTrue(v.message.contains("BLOCKED"))
    }

    @Test
    fun `a zero or negative budget means unlimited, not muted`() {
        // A graph author who omitted maxTurns must not silently get a node that
        // cannot use any tool.
        assertTrue(AgentToolBudget.check(0, 0, "R", "a").allowed)
        assertTrue(AgentToolBudget.check(999, 0, "R", "a").allowed)
        assertTrue(AgentToolBudget.check(999, -1, "R", "a").allowed)
    }

    @Test
    fun `a warning fires exactly one call before the wall`() {
        assertNull(AgentToolBudget.warningFor(0, 5))
        assertNull(AgentToolBudget.warningFor(3, 5))
        assertTrue(AgentToolBudget.warningFor(4, 5)!!.contains("One call remains"))
        // Not after: at 5 of 5 the refusal already speaks.
        assertNull(AgentToolBudget.warningFor(5, 5))
    }

    @Test
    fun `no warning when unbounded`() {
        assertNull(AgentToolBudget.warningFor(4, 0))
    }

    @Test
    fun `default budget is generous enough for a real multi-step edit`() {
        // Guards against someone "tightening" this into uselessness: the point
        // is stopping a 14-call wander, not blocking normal work.
        assertTrue(AgentToolBudget.DEFAULT_BUDGET >= 8)
    }

    @Test
    fun `a planner budget stops the measured wander`() {
        // Run 9eb70345 made 14 calls. With the built-in planner's maxTurns of 5,
        // call 6 must be refused.
        val plannerBudget = 5
        assertTrue(AgentToolBudget.check(4, plannerBudget, "Orchestrator", "a plan").allowed)
        assertFalse(AgentToolBudget.check(5, plannerBudget, "Orchestrator", "a plan").allowed)
        assertEquals(false, AgentToolBudget.check(13, plannerBudget, "Orchestrator", "a plan").allowed)
    }
}
