package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B49-spawn-timeout] The spawn_many schema tells the model the truth about
 * its limits and exposes the ceiling knob: a `timeout` argument (the vc163
 * incident had reasoning members die at the untouched 90s default), and no
 * stale "max 3 at once" promise while the real gate is
 * AgentConcurrency.parallel (2-6, thermal-stepped).
 */
class SubagentSpawnSchemaTest {

    private val def = SubagentTools.spawnManyDefinition()

    @Test
    fun `spawn_many exposes the timeout knob`() {
        assertTrue("timeout" in def.parameters)
        // propertyOrdering is nullable in the model (only some tools set it);
        // this definition always does — orEmpty() keeps the pin honest.
        assertTrue(def.propertyOrdering.orEmpty().contains("timeout"))
        assertTrue(def.parameters.getValue("timeout").type == "integer")
    }

    @Test
    fun `the description no longer promises a fixed concurrency`() {
        // The gate is dynamic (2-6 by device, thermal-stepped): a fixed
        // number in the schema teaches the model a lie.
        assertFalse(def.description.contains("max 3 at once"))
        assertTrue(def.description.contains("concurrency gate"))
    }
}
