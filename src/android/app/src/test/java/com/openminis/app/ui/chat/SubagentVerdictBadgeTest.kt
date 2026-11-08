package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [B34-UI] The verdict pill's parser: longest-first matching (a plain
 * "VERDICT: SHIP" must not shadow "SHIP_WITH_FIXES" — the substring trap),
 * the middle of a report does not trigger it twice, and a report without
 * a verdict line parses to null (workers carry no gate contract).
 */
class SubagentVerdictBadgeTest {

    @Test
    fun `ship_with_fixes wins over the ship substring`() {
        val report = "1. [MINOR] file:286 — use a local color.\n" +
            "VERDICT: SHIP_WITH_FIXES (minor non-blocking issues)"
        assertEquals(AgentVerdict.SHIP_WITH_FIXES, agentVerdictOf(report))
    }

    @Test
    fun `do_not_ship parses`() {
        assertEquals(AgentVerdict.DO_NOT_SHIP, agentVerdictOf("found a critical defect\nVERDICT: DO_NOT_SHIP"))
    }

    @Test
    fun `plain ship parses`() {
        assertEquals(AgentVerdict.SHIP, agentVerdictOf("all clear\nVERDICT: SHIP (no blockers found)"))
    }

    @Test
    fun `no verdict line parses to null`() {
        assertNull(agentVerdictOf("read five files, found nothing unusual"))
    }

    @Test
    fun `empty report parses to null`() {
        assertNull(agentVerdictOf(""))
    }
}
