package com.openminis.app.engine

import com.openminis.app.tools.jsonObjectResourceKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [B47-shell-parallel] The reviewer's acceptance pins, task 1: the false
 * GLOBAL barrier is gone — independent shell commands dispatch in
 * parallel under the CPU gate, while commands naming the SAME file keep
 * their model-order edges. All planning runs through the REAL extractor
 * (`jsonObjectResourceKeys`) — the production wiring, not a scripted
 * lambda.
 */
class ShellParallelDispatchTest {

    private fun planner() = ToolScheduler(
        resourceKeysOf = ::jsonObjectResourceKeys,
        maxConcurrent = 8,
    )

    private fun shell(id: String, command: String) =
        ToolScheduler.SchedCall(id, "shell_execute", """{"command":"$command"}""")

    private fun fileRead(id: String, path: String) =
        ToolScheduler.SchedCall(id, "file_read", """{"path":"$path"}""")

    private fun fileWrite(id: String, path: String) =
        ToolScheduler.SchedCall(id, "file_write", """{"path":"$path"}""")

    private fun depsOf(nodes: List<ToolScheduler.ReactiveNode>, id: String) =
        nodes.first { it.call.id == id }.dependsOn

    @Test
    fun `three independent shell commands dispatch with no deps`() {
        // The reviewer's exact pin: 3 shell_execute in planReactive get
        // dependsOn = emptySet — they start the moment the CPU gate has a
        // slot, not behind each other.
        val nodes = planner().planReactive(
            listOf(
                shell("s1", "ls -la"),
                shell("s2", "sleep 1"),
                shell("s3", "echo hello"),
            ),
        )
        assertEquals(emptySet<String>(), depsOf(nodes, "s1"))
        assertEquals(emptySet<String>(), depsOf(nodes, "s2"))
        assertEquals(emptySet<String>(), depsOf(nodes, "s3"))
    }

    @Test
    fun `two shells writing the same file serialize in model order`() {
        val nodes = planner().planReactive(
            listOf(
                shell("s1", "echo a > out.txt"),
                shell("s2", "echo b >> out.txt"),
            ),
        )
        assertEquals(setOf("s1"), depsOf(nodes, "s2"))
    }

    @Test
    fun `a shell and a file tool on the same path serialize`() {
        // The shell redirects into the path; the file_write that follows
        // must wait for the shell (WAR through the declared key).
        val nodes = planner().planReactive(
            listOf(
                shell("s1", "echo draft > /var/minis/workspace/out.txt"),
                fileWrite("w1", "/var/minis/workspace/out.txt"),
            ),
        )
        assertEquals(setOf("s1"), depsOf(nodes, "w1"))
    }

    @Test
    fun `shells on different files stay parallel`() {
        val nodes = planner().planReactive(
            listOf(
                shell("s1", "echo a > a.txt"),
                shell("s2", "echo b > b.txt"),
            ),
        )
        assertEquals(emptySet<String>(), depsOf(nodes, "s1"))
        assertEquals(emptySet<String>(), depsOf(nodes, "s2"))
    }

    @Test
    fun `a shell no longer blocks unrelated file tools`() {
        // The mixed-batch shape from the device report: a shell sandwiched
        // between reads must not seal the whole batch.
        val nodes = planner().planReactive(
            listOf(
                fileRead("r1", "a.rs"),
                shell("s1", "ls -la"),
                fileRead("r2", "b.rs"),
            ),
        )
        assertEquals(emptySet<String>(), depsOf(nodes, "s1"))
        assertEquals(emptySet<String>(), depsOf(nodes, "r2"))
    }
}
