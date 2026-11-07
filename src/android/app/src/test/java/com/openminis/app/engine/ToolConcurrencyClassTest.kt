package com.openminis.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P1-io-concurrency] The routing table for the two execution gates: I/O
 * tools get the high fan-out cap, everything with shared session state
 * (shell/browser/spawn/mcp) stays on the conservative CPU gate. A wrong
 * entry either starves parallelism (missing an IO tool) or breaks
 * isolation (listing a stateful tool).
 */
class ToolConcurrencyClassTest {

    @Test
    fun `file and network tools route to the io gate`() {
        for (tool in listOf("file_read", "file_write", "file_edit", "read_image", "webfetch", "memory_write")) {
            assertTrue("tool=$tool", ToolConcurrencyClass.isIo(tool))
        }
    }

    @Test
    fun `session-state tools stay on the cpu gate`() {
        for (tool in listOf("shell_execute", "browser_use", "spawn_subagent", "mcp", "bg_run")) {
            assertFalse("tool=$tool", ToolConcurrencyClass.isIo(tool))
        }
    }

    @Test
    fun `unknown tool is cpu-gated (conservative default)`() {
        assertFalse(ToolConcurrencyClass.isIo("something_new"))
    }
}
