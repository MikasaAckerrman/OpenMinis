package com.openminis.app.engine

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-m7-preflight] Direct engine-path tests on the normalized values.
 * The 28-case ToolPreflightTest covers the same semantics through the
 * org.json adapter; these pin the engine contract itself.
 */
class ToolPreflightEngineTest {

    private fun tool(
        params: Map<String, AgentToolParam>,
        required: List<String>,
    ) = AgentToolDefinition(
        name = "t", description = "d",
        parameters = params, required = required,
    )

    @Test
    fun `whole real is an integer, fraction is not`() {
        val t = tool(mapOf("n" to AgentToolParam(type = "integer", description = "")), listOf("n"))
        assertNull(ToolPreflight.validate("t", mapOf("n" to PreflightValue.RealNum(900.0)), t))
        assertNotNull(ToolPreflight.validate("t", mapOf("n" to PreflightValue.RealNum(12.5)), t))
    }

    @Test
    fun `explicit null is missing`() {
        val t = tool(mapOf("p" to AgentToolParam(type = "string", description = "")), listOf("p"))
        assertNotNull(ToolPreflight.validate("t", mapOf("p" to PreflightValue.Null), t))
    }

    @Test
    fun `enum mismatch names the list`() {
        val t = tool(
            mapOf("a" to AgentToolParam(type = "string", description = "", enumValues = listOf("x", "y"))),
            listOf("a"),
        )
        val err = ToolPreflight.validate("t", mapOf("a" to PreflightValue.Text("z")), t)
        assertNotNull(err)
        org.junit.Assert.assertTrue(err!!.contains("x, y"))
    }

    @Test
    fun `empty string legal where whitelisted`() {
        val t = tool(
            mapOf("new_string" to AgentToolParam(type = "string", description = "")),
            listOf("new_string"),
        )
        // whitelisted for file_edit.new_string — not for tool "t"
        assertNotNull(ToolPreflight.validate("t", mapOf("new_string" to PreflightValue.Text("")), t))
        val edit = AgentToolDefinition(
            name = "file_edit", description = "d",
            parameters = mapOf("new_string" to AgentToolParam(type = "string", description = "")),
            required = listOf("new_string"),
        )
        assertNull(ToolPreflight.validate("file_edit", mapOf("new_string" to PreflightValue.Text("")), edit))
    }
}
