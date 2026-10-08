package com.openminis.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-tool-scheduler] The conflict matrix from the user's spec (08.10),
 * each rule pinned by a test:
 *   read+read same resource -> parallel (one wave)
 *   read+write same resource -> serialize, model order (two waves)
 *   write+write same resource -> strict sequential (two waves)
 *   write+write disjoint resources -> parallel (one wave)
 *   GLOBAL (shell/undeclared) -> its own wave
 *   META (user/turn state) -> one per wave
 */
class ToolSchedulerTest {

    private fun sched(
        id: String,
        name: String,
        path: String? = null,
    ) = ToolScheduler.SchedCall(id, name, path?.let { """{"path":"$it"}""" } ?: "{}")

    private fun keys(toolName: String, argsJson: String): Set<String>? = when (toolName) {
        "file_read", "file_write", "file_edit", "read_image" -> {
            val p = org.json.JSONObject(argsJson).optString("path", "").trim()
            if (p.isEmpty()) null else setOf("fs:$p")
        }
        "memory_write", "memory_blocks_edit" -> setOf("memory:journal")
        else -> null
    }

    private fun planner() = ToolScheduler(resourceKeysOf = ::keys, maxConcurrent = 4)

    private fun names(waves: List<ToolScheduler.Wave>) = waves.map { w -> w.calls.joinToString(",") { it.name } }

    @Test
    fun `read plus read same file is one parallel wave`() {
        val w = planner().plan(listOf(sched("1", "file_read", "/a"), sched("2", "file_read", "/a")))
        assertEquals(1, w.size)
        assertEquals(2, w[0].calls.size)
    }

    @Test
    fun `read then write same file serializes in model order`() {
        val w = planner().plan(listOf(sched("1", "file_read", "/a"), sched("2", "file_write", "/a")))
        assertEquals(2, w.size)
        assertEquals("file_read", w[0].calls[0].name)
        assertEquals("file_write", w[1].calls[0].name)
    }

    @Test
    fun `write then read same file serializes in model order`() {
        val w = planner().plan(listOf(sched("1", "file_write", "/a"), sched("2", "file_read", "/a")))
        assertEquals(2, w.size)
        assertEquals("file_write", w[0].calls[0].name)
    }

    @Test
    fun `write plus write same file is strictly sequential`() {
        val w = planner().plan(listOf(sched("1", "file_edit", "/a"), sched("2", "file_write", "/a")))
        assertEquals(2, w.size)
    }

    @Test
    fun `write plus write disjoint files parallelize`() {
        val w = planner().plan(listOf(sched("1", "file_write", "/a"), sched("2", "file_write", "/b")))
        assertEquals(1, w.size)
        assertEquals(2, w[0].calls.size)
    }

    @Test
    fun `global tool runs alone between reads`() {
        val w = planner().plan(listOf(sched("1", "file_read", "/a"), sched("2", "shell_execute"), sched("3", "file_read", "/b")))
        // read+read could share a wave, but shell splits the batch in model
        // order: wave1 read, wave2 shell, wave3 read.
        assertEquals(3, w.size)
        assertEquals("shell_execute", w[1].calls[0].name)
    }

    @Test
    fun `undeclared resource tool is globally locked`() {
        // file_write with a BLANK path: extractor returns null -> GLOBAL.
        val w = planner().plan(listOf(sched("1", "file_write", ""), sched("2", "file_read", "/a")))
        assertEquals(2, w.size)
    }

    @Test
    fun `meta tools serialize among themselves`() {
        val w = planner().plan(listOf(sched("1", "todo_write"), sched("2", "todo_write")))
        assertEquals(2, w.size)
    }

    @Test
    fun `memory journal writes serialize`() {
        val w = planner().plan(listOf(sched("1", "memory_write"), sched("2", "memory_write")))
        assertEquals(2, w.size)
    }

    @Test
    fun `mixed batch - independent joins the open wave, conflicting goes next`() {
        val w = planner().plan(
            listOf(
                sched("1", "file_read", "/a"),
                sched("2", "file_write", "/b"),
                sched("3", "file_read", "/b"),
                sched("4", "file_read", "/c"),
            ),
        )
        // wave1: readA + writeB (disjoint). readB conflicts with writeB ->
        // wave2. readC joins wave2? readC (fs:/c) vs wave2 readB (fs:/b):
        // read+read no conflict -> wave2 = [readB, readC].
        assertEquals(2, w.size)
        assertEquals(listOf("file_read", "file_write"), w[0].calls.map { it.name })
        assertEquals(listOf("file_read", "file_read"), w[1].calls.map { it.name })
    }

    @Test
    fun `single call batch is one wave`() {
        val w = planner().plan(listOf(sched("1", "shell_execute")))
        assertEquals(1, w.size)
        assertEquals(1, w[0].calls.size)
    }

    @Test
    fun `empty batch has no waves`() {
        assertEquals(0, planner().plan(emptyList()).size)
    }

    @Test
    fun `cap is at least one`() {
        assertEquals(1, ToolScheduler(maxConcurrent = 0).cap())
        assertEquals(4, planner().cap())
    }

    @Test
    fun `unknown tool name is global`() {
        assertEquals(ToolScheduler.Effect.GLOBAL, ToolScheduler.effectOf("mystery_tool"))
        assertEquals(ToolScheduler.Effect.READ, ToolScheduler.effectOf("grep"))
        assertEquals(ToolScheduler.Effect.WRITE, ToolScheduler.effectOf("file_edit"))
    }

    @Test
    fun `default injection serializes everything`() {
        // The default resourceKeysOf (null for all) = conservative mode.
        val w = ToolScheduler().plan(
            listOf(sched("1", "file_read", "/a"), sched("2", "file_read", "/b")),
        )
        assertEquals(2, w.size)
    }
}
