package com.openminis.app.engine

import com.openminis.app.tools.PlanSubmitTool
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSubmitToolTest {

    private var approved = false

    private fun tool(answer: String) = PlanSubmitTool.Confirm { _, _ -> answer }

    private val args = """{"plan":"## Step 1\nread files\n## Step 2\nwrite fix","summary":"the fix"}"""

    private fun run(answer: String, json: String = args): ToolExecutionResult {
        approved = false
        return runBlocking {
            PlanSubmitTool.execute(json, "s1", tool(answer)) { approved = true }
        }
    }

    @Test
    fun `approval flips the session out of plan mode and echoes the plan`() {
        val r = run(PlanSubmitTool.APPROVE)
        assertTrue(r.success)
        assertTrue(approved)
        assertTrue(r.output.startsWith("PLAN APPROVED"))
        assertTrue(r.output.contains("read files"))
    }

    @Test
    fun `cancel keeps plan mode and refuses to unlock`() {
        val r = run(PlanSubmitTool.CANCEL)
        assertTrue(r.success)
        assertTrue(!approved)
        assertTrue(r.output.startsWith("PLAN REJECTED"))
    }

    @Test
    fun `free text is edit feedback - plan mode stays on`() {
        val r = run("skip step 2, do it in rust")
        assertTrue(r.success)
        assertTrue(!approved)
        assertTrue(r.output.startsWith("PLAN NOT APPROVED"))
        assertTrue(r.output.contains("do it in rust"))
    }

    @Test
    fun `blank answer keeps plan mode`() {
        val r = run("")
        assertTrue(r.success)
        assertTrue(!approved)
    }

    @Test
    fun `missing plan is a tool error`() {
        val r = run(PlanSubmitTool.APPROVE, """{"summary":"x"}""")
        assertTrue(!r.success)
        assertTrue(!approved)
    }

    @Test
    fun `malformed json is a tool error`() {
        val r = run(PlanSubmitTool.APPROVE, "not json")
        assertTrue(!r.success)
        assertTrue(!approved)
    }

    @Test
    fun `answer matching is case-insensitive`() {
        val r = run("approve")
        assertTrue(approved)
        assertTrue(r.output.startsWith("PLAN APPROVED"))
    }
}
