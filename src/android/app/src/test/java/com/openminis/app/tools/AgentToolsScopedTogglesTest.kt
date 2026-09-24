package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scoped-agent-toggles] Auto Mode / Subagents became PER-SESSION (user
 * request 24.09: "авто режим и мини агенты — только в определённой
 * сессии"). The chat menu / ▶ button write a session override; the VM
 * resolves override ?: legacy global and hands the boolean HERE — this test
 * pins the schema contract at the boundary: the parameter physically strips
 * the subagent machinery from the model's tool schema, so a session with
 * subagents off cannot even attempt a spawn (same discipline as the memory
 * gate). The parameter default keeps the legacy global read for
 * non-session callers (DebugRPCHandler).
 */
class AgentToolsScopedTogglesTest {

    private fun names(subagentsEnabled: Boolean): List<String> =
        AgentTools.makeAgentTools(subagentsEnabled = subagentsEnabled).map { it.name }

    @Test
    fun `subagents OFF strips spawn and graph tools from the schema`() {
        val n = names(subagentsEnabled = false)
        assertFalse(
            "spawn_subagent must not reach the schema when the session has subagents off",
            n.contains(SubagentTools.SPAWN_TOOL_NAME),
        )
        assertFalse(
            "run_graph must not reach the schema when the session has subagents off",
            n.contains(SubagentTools.RUN_GRAPH_TOOL_NAME),
        )
    }

    @Test
    fun `subagents ON exposes both subagent tools`() {
        val n = names(subagentsEnabled = true)
        assertTrue(
            "spawn_subagent must be present when the session has subagents on",
            n.contains(SubagentTools.SPAWN_TOOL_NAME),
        )
        assertTrue(
            "run_graph must be present when the session has subagents on",
            n.contains(SubagentTools.RUN_GRAPH_TOOL_NAME),
        )
    }

    @Test
    fun `unrelated tools are not collateral damage of the gate`() {
        val off = names(subagentsEnabled = false).toSet()
        val on = names(subagentsEnabled = true).toSet()
        // The gate must remove ONLY the subagent pair: everything else —
        // shell, file tools, browser, memory — is identical in both sets.
        val diff = off.symmetricDifference(on)
        assertTrue(
            "gate must strip exactly the subagent tools, got diff=$diff",
            diff == setOf(SubagentTools.SPAWN_TOOL_NAME, SubagentTools.RUN_GRAPH_TOOL_NAME),
        )
    }
}
