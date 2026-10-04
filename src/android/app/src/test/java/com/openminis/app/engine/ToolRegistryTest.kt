package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class FakeTool(
        override val name: String,
        override val mutation: MutationKind,
    ) : EngineTool {
        override fun definition(): AgentToolDefinition =
            AgentToolDefinition(name = name, description = "fake $name", parameters = emptyMap())

        override suspend fun execute(argsJson: String, ctx: ToolContext): ToolExecutionResult =
            ToolExecutionResult("ok", true)
    }

    @Test
    fun `duplicate registration is rejected`() {
        val r = ToolRegistry()
        r.register(FakeTool("a", MutationKind.READ))
        val ex = runCatching { r.register(FakeTool("a", MutationKind.WRITE)) }
            .exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `lookup by name`() {
        val r = ToolRegistry()
        r.register(FakeTool("a", MutationKind.READ))
        assertEquals("a", r.find("a")!!.name)
        assertNull(r.find("b"))
        assertEquals(setOf("a"), r.names())
    }

    @Test
    fun `plan mode strips write tools from the schema`() {
        val r = ToolRegistry()
        r.register(FakeTool("file_read", MutationKind.READ))
        r.register(FakeTool("file_write", MutationKind.WRITE))
        r.register(FakeTool("shell_execute", MutationKind.EXECUTE))

        val plan = r.schemaFor(DefaultPermissionGate(PermissionMode.PLAN))
        assertEquals(setOf("file_read", "shell_execute"), plan.map { it.name }.toSet())

        val edit = r.schemaFor(DefaultPermissionGate(PermissionMode.EDIT))
        assertEquals(setOf("file_read", "file_write", "shell_execute"), edit.map { it.name }.toSet())

        val auto = r.schemaFor(DefaultPermissionGate(PermissionMode.AUTO))
        assertEquals(3, auto.size)
    }
}
