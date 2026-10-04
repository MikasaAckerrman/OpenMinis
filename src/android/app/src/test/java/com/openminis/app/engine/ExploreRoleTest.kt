package com.openminis.app.engine

import com.openminis.app.data.model.AgentRole
import com.openminis.app.offload.SubagentRoles
import com.openminis.app.tools.SubagentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreRoleTest {

    /** The read-only surface an EXPLORE child is scoped to — no shell, no writes, no delegation. */
    private val readOnlyTools = setOf(
        "file_read", "read_image", "grep", "glob",
        "web_search", "webfetch", "memory_get", "todo_read",
    )

    @Test
    fun `EXPLORE is spawnable and first in the enum`() {
        assertEquals("EXPLORE", SubagentRoles.SPAWNABLE.first())
        assertTrue("EXPLORE" in SubagentRoles.SPAWNABLE)
    }

    @Test
    fun `spawn tool schema advertises EXPLORE`() {
        val roleValues = SubagentTools.spawnSubagentDefinition()
            .parameters["role"]!!.enumValues!!
        assertTrue("EXPLORE" in roleValues)
    }

    @Test
    fun `EXPLORE tool surface is exactly the read-only set`() {
        val tools = SubagentRoles.defaultToolsForRole(AgentRole.EXPLORE).toSet()
        assertEquals(readOnlyTools, tools)
    }

    @Test
    fun `EXPLORE can neither write nor execute nor delegate`() {
        val tools = SubagentRoles.defaultToolsForRole(AgentRole.EXPLORE).toSet()
        // A research child of a PLAN/EDIT parent must not be able to write
        // around the mode — the allowlist is the enforcement.
        assertFalse("shell_execute" in tools)
        assertFalse("file_write" in tools)
        assertFalse("file_edit" in tools)
        assertFalse("spawn_subagent" in tools)
        assertFalse("spawn_many" in tools)
        assertFalse("mcp" in tools)
    }

    @Test
    fun `writer roles keep their write surface - scoping is per-role`() {
        val coder = SubagentRoles.defaultToolsForRole(AgentRole.SENIOR_IMPLEMENTER).toSet()
        assertTrue("file_write" in coder)
        assertTrue("shell_execute" in coder)
    }
}
