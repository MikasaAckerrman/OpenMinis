package com.openminis.app.offload

import com.openminis.app.data.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-handoff-multi-to] Fan-out graphs (Parallel Research: research-entry
 * routes to researcher-a AND researcher-b) make the model address MULTIPLE
 * targets in the TO field — node ids ("researcher-a") or comma/and lists of
 * roles. The strict single-role valueOf rejected them -> null -> the whole
 * run FAILED on a perfectly-formed handoff (08.10 shadow-test repro: run
 * 95a83f22 died with PARSE_FAILURE twice on valid blocks). `to` has no
 * consumers (routing is edge-driven), so it is informational and OPTIONAL.
 */
class HandoffMultiToTest {

    private fun block(to: String) = """
        === HANDOFF START ===
        FROM: ORCHESTRATOR
        TO: $to
        TASK_ID: 95a83f22-e46e-49f8-b96e-d8537c89cd14
        STATUS: COMPLETE
        DELIVERABLES:
        - Routing instruction delivered.
        NEXT_REQUIRED_ACTION:
        Both researchers work independently.
        === HANDOFF END ===
    """.trimIndent()

    @Test
    fun `comma-separated fan-out targets parse with the first resolvable role`() {
        val h = HandoffValidator.parseHandoff(block("CODEBASE_DISCOVERY, EXPLORE"))
        assertNotNull(h)
        assertEquals(AgentRole.CODEBASE_DISCOVERY, h!!.to)
    }

    @Test
    fun `and-separated fan-out targets parse`() {
        val h = HandoffValidator.parseHandoff(block("researcher-a and researcher-b"))
        assertNotNull(h)
        // Neither "researcher-a" nor "researcher-b" is an AgentRole value:
        // the handoff is STILL valid, `to` is informational-null.
        assertNull(h.to)
        assertEquals("95a83f22-e46e-49f8-b96e-d8537c89cd14", h.taskId)
    }

    @Test
    fun `node-id target keeps the handoff valid`() {
        val h = HandoffValidator.parseHandoff(block("researcher-b"))
        assertNotNull(h)
        assertNull(h!!.to)
        assertTrue(h.deliverables.isNotEmpty())
    }

    @Test
    fun `single valid role still resolves`() {
        val h = HandoffValidator.parseHandoff(block("FINAL_GATEKEEPER"))
        assertNotNull(h)
        assertEquals(AgentRole.FINAL_GATEKEEPER, h.to)
    }

    @Test
    fun `validateResponse accepts a fan-out handoff end to end`() {
        val v = HandoffValidator.validateResponse(block("researcher-a, researcher-b"))
        assertTrue(v.isValid)
        assertNotNull(v.handoff)
    }
}
