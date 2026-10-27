package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRolesDelegationTest {

    @Test
    fun `main chat may arm an orchestrator and a reviewer`() {
        assertTrue(SubagentRoles.mayDelegate(AgentRole.ORCHESTRATOR, null))
        assertTrue(SubagentRoles.mayDelegate(AgentRole.SECURITY_REVIEWER, null))
        assertFalse(SubagentRoles.mayDelegate(AgentRole.EXPLORE, null))
    }

    @Test
    fun `an orchestrator may hire a worker`() {
        assertTrue(
            "the orchestrator is the entry delegator, it must be able to hire workers",
            SubagentRoles.mayDelegate(AgentRole.SENIOR_IMPLEMENTER, AgentRole.ORCHESTRATOR),
        )
        assertTrue(SubagentRoles.mayDelegate(AgentRole.EXPLORE, AgentRole.ORCHESTRATOR))
        assertTrue(SubagentRoles.mayDelegate(AgentRole.SOLUTION_ARCHITECT, AgentRole.ORCHESTRATOR))
    }

    @Test
    fun `an orchestrator may still hire a reviewer`() {
        assertTrue(
            SubagentRoles.mayDelegate(AgentRole.CODE_CORRECTNESS_REVIEWER, AgentRole.ORCHESTRATOR),
        )
    }

    @Test
    fun `an orchestrator cannot arm another orchestrator`() {
        assertFalse(
            "the depth cap must hold",
            SubagentRoles.mayDelegate(AgentRole.ORCHESTRATOR, AgentRole.ORCHESTRATOR),
        )
    }

    @Test
    fun `a worker arms nobody`() {
        assertFalse(SubagentRoles.mayDelegate(AgentRole.SENIOR_IMPLEMENTER, AgentRole.SENIOR_IMPLEMENTER))
        assertFalse(SubagentRoles.mayDelegate(AgentRole.EXPLORE, AgentRole.SENIOR_IMPLEMENTER))
        assertFalse(SubagentRoles.mayDelegate(AgentRole.SECURITY_REVIEWER, AgentRole.SENIOR_IMPLEMENTER))
    }

    @Test
    fun `a reviewer cannot arm a reviewer`() {
        assertFalse(
            SubagentRoles.mayDelegate(AgentRole.SECURITY_REVIEWER, AgentRole.SECURITY_REVIEWER),
        )
    }
}
