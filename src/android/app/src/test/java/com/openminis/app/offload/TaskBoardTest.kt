package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-task-board] The board shape is a contract between executor, workers and
 * synthesizer — pinned here.
 */
class TaskBoardTest {

    @Test
    fun `header lists every agent in order`() {
        val h = TaskBoard.header(listOf("reviewer", "custom:api-auditor"))
        assertTrue(h.contains("# Task board"))
        assertTrue(h.contains("- 1. reviewer"))
        assertTrue(h.contains("- 2. custom:api-auditor"))
    }

    @Test
    fun `entry carries order, role and truncated result`() {
        val e = TaskBoard.entry(3, "reviewer", "x".repeat(5000))
        assertTrue(e.startsWith("### 3. reviewer"))
        assertTrue(e.length < 5000)
        assertTrue(e.length <= "### 3. reviewer\n".length + TaskBoard.ENTRY_MAX_CHARS + 2)
    }

    @Test
    fun `empty snapshot leaves the task untouched`() {
        assertEquals("do the thing", TaskBoard.inject("do the thing", ""))
        assertEquals("do the thing", TaskBoard.inject("do the thing", "   "))
    }

    @Test
    fun `snapshot is injected under an explicit header`() {
        val out = TaskBoard.inject("do the thing", "### 1. reviewer\nfound X")
        assertTrue(out.startsWith("do the thing"))
        assertTrue(out.contains("FINDINGS SO FAR"))
        assertTrue(out.contains("found X"))
    }

    @Test
    fun `huge snapshots are capped so the worker prompt stays bounded`() {
        val out = TaskBoard.inject("task", "y".repeat(50_000))
        assertTrue(out.length < 50_000)
        assertTrue(out.length <= "task".length + 200 + TaskBoard.SNAPSHOT_MAX_CHARS)
    }
}
