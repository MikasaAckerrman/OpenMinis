package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-role-prompts] Every role needs an instruction of its own. Four roles
 * (FINAL_GATEKEEPER, INDEPENDENT_TEST_DESIGNER, DEPENDENCY_GUARDIAN,
 * DOCUMENTATION_AGENT) had none and fell through to the generic prompt,
 * which told them nothing about the job they were given.
 */
class AgentRolePromptsTest {

    /** The sentence the generic prompt is built from. */
    private val genericMarker = "Focus exclusively on this task"

    @Test
    fun `every role has a prompt of its own`() {
        for (role in AgentRole.values()) {
            val prompt = SubagentExecutor.promptForRole(role, "THE TASK")
            assertFalse(
                "role $role still uses the generic prompt",
                prompt.contains(genericMarker),
            )
        }
    }

    @Test
    fun `every prompt carries the task it was given`() {
        for (role in AgentRole.values()) {
            assertTrue(
                "role $role lost the task",
                SubagentExecutor.promptForRole(role, "THE TASK").contains("THE TASK"),
            )
        }
    }

    @Test
    fun `the gate role ends with a verdict it can act on`() {
        val prompt = SubagentExecutor.promptForRole(AgentRole.FINAL_GATEKEEPER, "THE TASK")
        assertTrue("no SHIP verdict", prompt.contains("VERDICT: SHIP"))
        assertTrue("no SHIP_WITH_FIXES verdict", prompt.contains("VERDICT: SHIP_WITH_FIXES"))
        assertTrue("no DO_NOT_SHIP verdict", prompt.contains("VERDICT: DO_NOT_SHIP"))
        assertTrue(
            "the gate must point at the code",
            prompt.contains("file and line"),
        )
    }

    @Test
    fun `the gate role does not fix the work itself`() {
        val prompt = SubagentExecutor.promptForRole(AgentRole.FINAL_GATEKEEPER, "THE TASK")
        assertTrue(
            "the gate must not rewrite the work",
            prompt.contains("Do not rewrite the work"),
        )
    }

    @Test
    fun `the test designer is told to cover the paths nobody checks`() {
        val prompt = SubagentExecutor.promptForRole(AgentRole.INDEPENDENT_TEST_DESIGNER, "THE TASK")
        assertTrue("no boundary requirement", prompt.contains("boundary"))
        assertTrue("no cancelled path", prompt.contains("cancelled"))
    }
}
