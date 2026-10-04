package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {

    /** Test write policy: only commands containing "rm" mutate. */
    private val policy = WritePolicy { cmd -> cmd.contains("rm") }

    private fun gate(mode: PermissionMode, allowlist: (String, String) -> Boolean = { _, _ -> false }) =
        DefaultPermissionGate(mode = mode, writePolicy = policy, allowlist = allowlist)

    @Test
    fun `plan allows read network and meta tools`() {
        val g = gate(PermissionMode.PLAN)
        assertEquals(GateDecision.Allow, g.check("file_read", ToolTaxonomy.kindOf("file_read"), "{}"))
        assertEquals(GateDecision.Allow, g.check("web_search", ToolTaxonomy.kindOf("web_search"), "{}"))
        assertEquals(GateDecision.Allow, g.check("todo_write", ToolTaxonomy.kindOf("todo_write"), "{}"))
    }

    @Test
    fun `plan denies write tools with a plan-mode instruction`() {
        val g = gate(PermissionMode.PLAN)
        val d = g.check("file_write", ToolTaxonomy.kindOf("file_write"), """{"path":"/x"}""")
        assertTrue(d is GateDecision.Deny)
        assertTrue((d as GateDecision.Deny).reason.contains("PLAN MODE"))
    }

    @Test
    fun `plan allows a provably read-only shell call`() {
        val g = gate(PermissionMode.PLAN)
        val d = g.check("shell_execute", ToolTaxonomy.kindOf("shell_execute"), """{"command":"ls -la"}""")
        assertEquals(GateDecision.Allow, d)
    }

    @Test
    fun `plan denies a mutating shell call`() {
        val g = gate(PermissionMode.PLAN)
        val d = g.check("shell_execute", ToolTaxonomy.kindOf("shell_execute"), """{"command":"rm -rf /x"}""")
        assertTrue(d is GateDecision.Deny)
    }

    @Test
    fun `plan strips write tools from the schema but keeps execute visible`() {
        val g = gate(PermissionMode.PLAN)
        assertFalse(g.visibleInSchema(MutationKind.WRITE))
        assertTrue(g.visibleInSchema(MutationKind.EXECUTE))
        assertTrue(g.visibleInSchema(MutationKind.READ))
    }

    @Test
    fun `edit asks for a mutating execution and allows a read-only one`() {
        val g = gate(PermissionMode.EDIT)
        assertEquals(GateDecision.Ask,
            g.check("shell_execute", MutationKind.EXECUTE, """{"command":"rm -rf /x"}"""))
        assertEquals(GateDecision.Allow,
            g.check("shell_execute", MutationKind.EXECUTE, """{"command":"ls -la"}"""))
    }

    @Test
    fun `edit allows allowlisted calls`() {
        val g = gate(PermissionMode.EDIT) { tool, _ -> tool == "shell_execute" }
        assertEquals(GateDecision.Allow,
            g.check("shell_execute", MutationKind.EXECUTE, """{"command":"rm -rf /x"}"""))
    }

    @Test
    fun `edit allows writes without asking`() {
        val g = gate(PermissionMode.EDIT)
        assertEquals(GateDecision.Allow, g.check("file_write", MutationKind.WRITE, "{}"))
    }

    @Test
    fun `auto allows everything`() {
        val g = gate(PermissionMode.AUTO)
        assertEquals(GateDecision.Allow, g.check("file_write", MutationKind.WRITE, "{}"))
        assertEquals(GateDecision.Allow,
            g.check("shell_execute", MutationKind.EXECUTE, """{"command":"rm -rf /x"}"""))
    }

    @Test
    fun `unknown tools classify as execute - conservative in plan and edit`() {
        val unknown = ToolTaxonomy.kindOf("totally_made_up_tool")
        assertEquals(MutationKind.EXECUTE, unknown)
        assertTrue(gate(PermissionMode.PLAN).check("totally_made_up_tool", unknown, "{}") is GateDecision.Deny)
        assertEquals(GateDecision.Ask, gate(PermissionMode.EDIT).check("totally_made_up_tool", unknown, "{}"))
    }

    @Test
    fun `malformed args json is treated as mutating for execute`() {
        val g = gate(PermissionMode.EDIT)
        assertEquals(GateDecision.Ask,
            g.check("shell_execute", MutationKind.EXECUTE, "not json"))
    }
}
