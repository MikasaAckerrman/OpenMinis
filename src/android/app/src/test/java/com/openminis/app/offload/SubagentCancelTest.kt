package com.openminis.app.offload

import com.openminis.app.tools.SubagentTools
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-agent-cancel] Contract of cancelling a background subagent.
 *
 * A background agent used to be uncancellable: the map of running agents
 * held a preview string and no handle to the work. A spawn that had gone
 * wrong kept its slot, its model calls and its share of the concurrency
 * budget until the whole task was over, and there was no way to intervene.
 *
 * The contract that matters to the caller - the model, through the
 * cancel_subagent tool - is not just "does it stop". It is also "does it
 * tell the truth". A cancel that reports success for an agent which had
 * already finished is worse than no cancel at all: the model would then
 * believe the agent was stopped when it had in fact run to completion.
 */
class SubagentCancelTest {

    @Test
    fun `cancelling a running background agent stops it`() {
        val job = Job()
        SubagentExecutor.registerBackgroundForTest("spawn-cancel-running", job)

        val stopped = SubagentExecutor.cancelBackground("spawn-cancel-running")

        assertTrue("a running agent must be reported as stopped", stopped)
        assertTrue("the job must actually be cancelled", job.isCancelled)
    }

    @Test
    fun `cancelling twice reports that there is nothing left to stop`() {
        val job = Job()
        SubagentExecutor.registerBackgroundForTest("spawn-cancel-twice", job)

        assertTrue(SubagentExecutor.cancelBackground("spawn-cancel-twice"))
        assertFalse(
            "the second cancel must not claim to have stopped a running agent",
            SubagentExecutor.cancelBackground("spawn-cancel-twice"),
        )
    }

    @Test
    fun `cancelling an agent that already finished is not a success`() {
        val job = Job().also { it.complete() }
        SubagentExecutor.registerBackgroundForTest("spawn-cancel-finished", job)

        assertFalse(
            "a finished agent has nothing left to stop",
            SubagentExecutor.cancelBackground("spawn-cancel-finished"),
        )
    }

    @Test
    fun `cancelling an unknown id is not an error`() {
        assertFalse(SubagentExecutor.cancelBackground("spawn-never-existed"))
    }

    @Test
    fun `scope all cancels a foreground batch child`() {
        val job = Job()
        SubagentExecutor.registerForegroundForTest("spawn-fg-running", job)

        val stopped = SubagentExecutor.cancelSpawn("spawn-fg-running", includeForeground = true)

        assertTrue("a running foreground child must be reported as stopped", stopped)
        assertTrue("the job must actually be cancelled", job.isCancelled)
    }

    @Test
    fun `default scope leaves a foreground batch child alone`() {
        val job = Job()
        SubagentExecutor.registerForegroundForTest("spawn-fg-default", job)

        assertFalse(
            "background-scope cancel must not reach a foreground child",
            SubagentExecutor.cancelSpawn("spawn-fg-default", includeForeground = false),
        )
        assertFalse("the child must keep running", job.isCancelled)
        job.cancel()
    }

    @Test
    fun `foreground cancel of a finished child is honest`() {
        val job = Job().also { it.complete() }
        SubagentExecutor.registerForegroundForTest("spawn-fg-finished", job)

        assertFalse(
            "a finished foreground child has nothing left to stop",
            SubagentExecutor.cancelSpawn("spawn-fg-finished", includeForeground = true),
        )
    }

    @Test
    fun `foreground child is visible to isCancellable only under scope all`() {
        val job = Job()
        SubagentExecutor.registerForegroundForTest("spawn-fg-visible", job)

        assertFalse(
            "the plain panel probe must not report a foreground child",
            SubagentExecutor.isCancellable("spawn-fg-visible"),
        )
        assertTrue(
            "scope=all must see the foreground child",
            SubagentExecutor.isCancellable("spawn-fg-visible", includeForeground = true),
        )
        job.cancel()
    }

    @Test
    fun `the cancel tool asks for a spawn id and a title`() {
        val def = SubagentTools.cancelSubagentDefinition()

        assertEquals("cancel_subagent", def.name)
        assertTrue("spawn_id must be required", def.required.contains("spawn_id"))
        assertTrue("tool_title must be required", def.required.contains("tool_title"))
        assertEquals("string", def.parameters["spawn_id"]?.type)
    }
}
