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

/**
 * [B48-session-shell] The session-scoped key the production wiring
 * (ChatViewModel's resourceKeysOf lambda) adds to every shell: one key per
 * SESSION, so same-session shells take turns on the DAG instead of racing
 * the executor's per-session mutex while holding CPU-gate slots, and
 * cross-session shells (worker sessions) stay parallel. The file-conflict
 * keys ride underneath unchanged.
 */
class SessionShellKeyTest {

    /** The production wiring shape: base keys + the session-shell key. */
    private fun sessionPlanner(sid: String) = ToolScheduler(
        resourceKeysOf = { name, argsJson ->
            val base = jsonObjectResourceKeys(name, argsJson)
            if (name == "shell_execute") {
                (base ?: emptySet()) + "session-shell:$sid"
            } else base
        },
        maxConcurrent = 8,
    )

    private fun shell(id: String, command: String) =
        ToolScheduler.SchedCall(id, "shell_execute", """{"command":"$command"}""")

    private fun fileWrite(id: String, path: String) =
        ToolScheduler.SchedCall(id, "file_write", """{"path":"$path"}""")

    private fun depsOf(nodes: List<ToolScheduler.ReactiveNode>, id: String) =
        nodes.first { it.call.id == id }.dependsOn

    @Test
    fun `two same-session shells take turns on the dag`() {
        val nodes = sessionPlanner("s1").planReactive(
            listOf(
                shell("a", "ls -la"),
                shell("b", "cat /etc/hostname"),
            ),
        )
        // Same session, one /bin/sh: the second waits for the first on the
        // session key instead of queueing at the executor's mutex with a
        // held CPU-gate slot.
        assertEquals(setOf("a"), depsOf(nodes, "b"))
    }

    @Test
    fun `a lone shell in its own session waits on nothing`() {
        // Cross-session parallelism is architectural: every session runs
        // its OWN EngineAgentLoop with its OWN scheduler, so two worker
        // sessions never land in one DAG. Each session's lone shell —
        // stamped session-shell:<sid> by the wiring — starts immediately.
        val s1Nodes = sessionPlanner("s1").planReactive(listOf(shell("a", "ls -la")))
        val s2Nodes = sessionPlanner("s2").planReactive(listOf(shell("b", "ls -la")))
        assertEquals(emptySet<String>(), depsOf(s1Nodes, "a"))
        assertEquals(emptySet<String>(), depsOf(s2Nodes, "b"))
    }

    @Test
    fun `the session key does not weaken file-conflict ordering`() {
        val nodes = sessionPlanner("s1").planReactive(
            listOf(
                shell("a", "echo x > out.txt"),
                fileWrite("w", "out.txt"),
            ),
        )
        // The file key rides UNDERNEATH the session key: the write still
        // waits for the shell that redirects into the same file.
        assertEquals(setOf("a"), depsOf(nodes, "w"))
    }
}
