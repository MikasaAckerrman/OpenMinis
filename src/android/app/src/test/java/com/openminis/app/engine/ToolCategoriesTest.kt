package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B16] The category table is the SINGLE structural source of the canonical
 * allowlist-able tool set — AgentTools.ALL_TOOL_NAMES and the agent-file
 * parser both derive from it. These tests pin its internal consistency and,
 * critically, its agreement with the tool dispatcher (ChatViewModel's
 * `when (name)` switch, transcribed verbatim below). If a branch is added
 * there without a category here (or vice versa), the schema registry and
 * the dispatcher drift apart and one of them starts lying to the model.
 */
class ToolCategoriesTest {

    /**
     * Every branch of the dispatch switch in ChatViewModel.executeToolImpl
     * (literal + tool-object NAME constants resolved). The ONE deliberate
     * difference from ToolCategories.ALL_TOOLS is `plan_submit`: it is a
     * dispatcher branch but a PLAN-mode artifact, never allowlist-able.
     */
    private val dispatcherNames = setOf(
        "file_read", "file_write", "file_edit", "read_image",
        "shell_execute", "browser_use",
        "spawn_subagent", "cancel_subagent", "await_result", "spawn_many",
        "list_agents", "turn_timer", "run_graph", "task_board", "session_gc",
        "bg_run", "bg_check", "bg_list", "bg_kill", "bg_steer",
        "web_search", "ask_user", "todo_write", "todo_read", "plan_submit",
        "mcp", "webfetch", "grep", "glob",
        "memory_write", "memory_get", "supermemory_search",
        "root_shell", "memory_blocks_view", "memory_blocks_edit",
    )

    @Test
    fun `every schema-backed tool is in exactly one category`() {
        val seen = HashSet<String>()
        for (category in ToolCategories.CATEGORIES) {
            for (tool in category.tools) {
                assertTrue(
                    "tool '$tool' appears in more than one category",
                    seen.add(tool),
                )
            }
        }
        assertEquals(ToolCategories.ALL_TOOLS, seen)
        assertEquals("expected the full 34-tool registry", 34, seen.size)
    }

    @Test
    fun `categories cover the dispatcher minus plan_submit`() {
        val missing = dispatcherNames - ToolCategories.ALL_TOOLS - "plan_submit"
        assertTrue(
            "dispatcher tools without a category: $missing",
            missing.isEmpty(),
        )
        // The only allowed asymmetry: the plan-mode exit.
        val extra = ToolCategories.ALL_TOOLS - dispatcherNames
        assertTrue(
            "categorized tools the dispatcher cannot execute: $extra",
            extra.isEmpty(),
        )
    }

    @Test
    fun `plan_submit and subagent_task are deliberately excluded`() {
        assertFalse("plan_submit" in ToolCategories.ALL_TOOLS)
        assertFalse("subagent_task" in ToolCategories.ALL_TOOLS)
    }

    @Test
    fun `categoryOf resolves and rejects`() {
        assertEquals(ToolCategories.FILE, ToolCategories.categoryOf("file_read"))
        assertEquals(ToolCategories.SHELL, ToolCategories.categoryOf("bg_run"))
        assertEquals(ToolCategories.WEB, ToolCategories.categoryOf("webfetch"))
        assertEquals(ToolCategories.MEMORY, ToolCategories.categoryOf("memory_blocks_edit"))
        assertEquals(ToolCategories.SUBAGENT, ToolCategories.categoryOf("spawn_many"))
        assertEquals(ToolCategories.SYSTEM, ToolCategories.categoryOf("mcp"))
        assertNull(ToolCategories.categoryOf("phaser"))
        assertNull(ToolCategories.categoryOf("plan_submit"))
        assertNotNull(ToolCategories.categoryOf("file_read")!!.title)
    }

    @Test
    fun `all six spec categories are present`() {
        assertEquals(
            listOf("file", "shell", "web", "memory", "subagent", "system"),
            ToolCategories.CATEGORIES.map { it.key },
        )
    }
}
