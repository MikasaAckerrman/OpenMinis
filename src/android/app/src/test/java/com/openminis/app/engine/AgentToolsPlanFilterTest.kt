package com.openminis.app.engine

import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsPlanFilterTest {

    private fun tools(planMode: Boolean): List<String> =
        AgentTools.makeAgentTools(
            supportsImageInput = false,
            memoryEnabled = true,
            allowedTools = null,
            subagentsEnabled = false,
            coreMemoryEnabled = false,
            rootShellEnabled = false,
            planMode = planMode,
        ).map { it.name }

    @Test
    fun `plan mode strips write tools from the schema`() {
        val names = tools(planMode = true)
        assertFalse("file_write" in names)
        assertFalse("file_edit" in names)
        assertFalse("memory_write" in names)
    }

    @Test
    fun `plan mode keeps read and execute tools visible`() {
        val names = tools(planMode = true)
        assertTrue("file_read" in names)
        assertTrue("shell_execute" in names)
        assertTrue("todo_read" in names)
    }

    @Test
    fun `plan mode adds plan_submit as the mode exit`() {
        assertTrue("plan_submit" in tools(planMode = true))
    }

    @Test
    fun `default mode keeps write tools and has no plan_submit`() {
        val names = tools(planMode = false)
        assertTrue("file_write" in names)
        assertFalse("plan_submit" in names)
    }
}
