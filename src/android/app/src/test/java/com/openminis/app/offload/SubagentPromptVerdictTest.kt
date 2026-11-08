package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B34-UI / reviewer protocol] The reviewer personas carry the explicit
 * gate-verdict contract and the read-only statement: findings with
 * severity, a terminal VERDICT line, and the "single writer" rule — the
 * reviewer never edits code. Non-reviewer roles must NOT carry the
 * verdict contract (a worker ending its report with VERDICT: SHIP is
 * role confusion, not discipline).
 */
class SubagentPromptVerdictTest {

    private fun prompt(role: AgentRole) = SubagentExecutor.promptForRole(role, "review the diff")

    @Test
    fun `correctness reviewer carries verdict contract and read-only rule`() {
        val p = prompt(AgentRole.CODE_CORRECTNESS_REVIEWER)
        assertTrue(p.contains("VERDICT: SHIP"))
        assertTrue(p.contains("VERDICT: SHIP_WITH_FIXES"))
        assertTrue(p.contains("VERDICT: DO_NOT_SHIP"))
        assertTrue(p.contains("CANNOT edit files"))
        assertTrue(p.contains("BLOCKER"))
    }

    @Test
    fun `security reviewer carries verdict contract and read-only rule`() {
        val p = prompt(AgentRole.SECURITY_REVIEWER)
        assertTrue(p.contains("VERDICT: SHIP"))
        assertTrue(p.contains("VERDICT: SHIP_WITH_FIXES"))
        assertTrue(p.contains("VERDICT: DO_NOT_SHIP"))
        assertTrue(p.contains("CANNOT edit files"))
        assertTrue(p.contains("BLOCKER"))
    }

    @Test
    fun `final gatekeeper keeps its pre-existing verdict contract`() {
        val p = prompt(AgentRole.FINAL_GATEKEEPER)
        assertTrue(p.contains("VERDICT: SHIP"))
        assertTrue(p.contains("VERDICT: DO_NOT_SHIP"))
    }

    @Test
    fun `a worker role does not carry the verdict contract`() {
        val p = prompt(AgentRole.EXPLORE)
        assertFalse("EXPLORE is a worker — no gate verdicts", p.contains("VERDICT:"))
    }
}
