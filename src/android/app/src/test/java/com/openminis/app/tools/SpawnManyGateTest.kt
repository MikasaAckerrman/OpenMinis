package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-review-gate] A batch can end with a gate: a gatekeeper reads the work
 * and the board and returns a verdict, so the orchestrator cannot call an
 * implementation done without one.
 */
class SpawnManyGateTest {

    @Test
    fun `the spawn_many schema offers the gate`() {
        val gate = SubagentTools.spawnManyDefinition().parameters["gate"]
        assertNotNull("the gate parameter is missing", gate)
        assertEquals("boolean", gate!!.type)
    }

    @Test
    fun `the gate is described with its verdicts`() {
        val description = SubagentTools.spawnManyDefinition().parameters["gate"]!!.description
        assertTrue("no SHIP verdict", description.contains("SHIP"))
        assertTrue("no DO_NOT_SHIP verdict", description.contains("DO_NOT_SHIP"))
    }

    @Test
    fun `the gate stays optional`() {
        val def = SubagentTools.spawnManyDefinition()
        assertTrue("the gate must not be required", !def.required.contains("gate"))
    }
}
