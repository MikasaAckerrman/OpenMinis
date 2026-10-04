package com.openminis.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m7-batch-planner] The conflict rule as a declarative matrix —
 * previously ~40 untestable inline lines inside ChatViewModel.
 */
class ToolBatchPlannerTest {

    // fake extractor: reads the "path" the test embedded in argsJson
    private val planner = ToolBatchPlanner(
        pathOf = { _, args -> Regex("\"path\"\\s*:\\s*\"([^\"]*)\"")
            .find(args)?.groupValues?.get(1) ?: "" },
    )

    private fun call(id: String, name: String, path: String = "") =
        ToolBatchPlanner.PendingToolCall(id, name, if (path.isEmpty()) "{}" else "{\"path\":\"$path\"}")

    @Test
    fun `independent file reads go parallel`() {
        assertTrue(
            planner.canParallelize(
                listOf(call("1", "file_read", "/a.kt"), call("2", "file_read", "/b.kt")),
            ),
        )
    }

    @Test
    fun `single call stays sequential`() {
        assertFalse(planner.canParallelize(listOf(call("1", "file_read", "/a.kt"))))
    }

    @Test
    fun `duplicate path demotes the batch regardless of kind`() {
        // read + write on ONE path: order matters
        assertFalse(
            planner.canParallelize(
                listOf(call("1", "file_read", "/same.kt"), call("2", "file_edit", "/same.kt")),
            ),
        )
        // write + write on ONE path: last-write-wins would race
        assertFalse(
            planner.canParallelize(
                listOf(call("1", "file_write", "/same.kt"), call("2", "file_write", "/same.kt")),
            ),
        )
        // same tool, distinct paths: still fine
        assertTrue(
            planner.canParallelize(
                listOf(call("1", "file_write", "/a.kt"), call("2", "file_write", "/b.kt")),
            ),
        )
    }

    @Test
    fun `shell browser subagent bg mcp never join`() {
        for (unsafe in listOf("shell_execute", "browser_use", "spawn_subagent", "bg_run", "mcp", "todo_write")) {
            assertFalse(
                "tool=$unsafe",
                planner.canParallelize(
                    listOf(call("1", "file_read", "/a.kt"), call("2", unsafe, "/b.kt")),
                ),
            )
        }
    }

    @Test
    fun `memory_get has no conflict key and cannot conflict`() {
        // two memory_gets + a file read: nothing shares a path
        assertTrue(
            planner.canParallelize(
                listOf(call("1", "memory_get"), call("2", "memory_get"), call("3", "file_read", "/a.kt")),
            ),
        )
    }

    @Test
    fun `read_image participates by path`() {
        assertTrue(
            planner.canParallelize(
                listOf(call("1", "read_image", "/a.png"), call("2", "file_read", "/a.kt")),
            ),
        )
        assertFalse(
            planner.canParallelize(
                listOf(call("1", "read_image", "/a.png"), call("2", "file_edit", "/a.png")),
            ),
        )
    }

    @Test
    fun `real json extractor reads the path field`() {
        val p = ToolBatchPlanner(pathOf = ::jsonObjectPathExtractor)
        assertTrue(
            p.canParallelize(
                listOf(
                    ToolBatchPlanner.PendingToolCall("1", "file_read", "{\"path\":\"/x.kt\"}"),
                    ToolBatchPlanner.PendingToolCall("2", "file_edit", "{\"path\":\"/y.kt\"}"),
                ),
            ),
        )
        assertFalse(
            p.canParallelize(
                listOf(
                    ToolBatchPlanner.PendingToolCall("1", "file_read", "{\"path\":\"/x.kt\"}"),
                    ToolBatchPlanner.PendingToolCall("2", "file_edit", "{\"path\":\"/x.kt\"}"),
                ),
            ),
        )
    }

    @Test
    fun `malformed args are not a conflict`() {
        // broken JSON yields "" from the real extractor — cannot conflict,
        // the preflight layer is the one that rejects the call itself.
        val p = ToolBatchPlanner(pathOf = ::jsonObjectPathExtractor)
        assertTrue(
            p.canParallelize(
                listOf(
                    ToolBatchPlanner.PendingToolCall("1", "file_read", "{not json"),
                    ToolBatchPlanner.PendingToolCall("2", "file_read", "{\"path\":\"/a.kt\"}"),
                ),
            ),
        )
    }
}
