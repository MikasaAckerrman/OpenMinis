package com.openminis.app.engine

import com.openminis.app.tools.jsonObjectResourceKeys
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

    private fun planner() =
        ToolScheduler(resourceKeysOf = { name, args -> keys(name, args) }, maxConcurrent = 4)

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
    fun `global tool runs alone and seals its wave`() {
        val w = planner().plan(listOf(sched("1", "file_read", "/a"), sched("2", "shell_execute"), sched("3", "file_read", "/b")))
        // The shell's wave is sealed (nothing rides alongside an undeclared
        // effect). readB is INDEPENDENT of the shell (no shared resource) so
        // it may join wave1 — waves run in order: [readA+readB] then [shell].
        assertEquals(2, w.size)
        assertEquals("shell_execute", w[1].calls[0].name)
        assertEquals(2, w[0].calls.size)
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
        // wave1: readA (fs:a) + writeB (fs:b) + readC (fs:c) — all disjoint
        // resources, read/write on DIFFERENT files do not conflict.
        // readB (fs:b) conflicts with writeB in wave1 -> wave2.
        assertEquals(2, w.size)
        assertEquals(listOf("file_read", "file_write", "file_read"), w[0].calls.map { it.name })
        assertEquals(listOf("file_read"), w[1].calls.map { it.name })
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

    @Test
    fun `two spawns share one wave`() {
        // [T-parallel-agents] A spawn used to be an undeclared (GLOBAL) call,
        // so it got a sealed wave of its own and two spawns in one turn could
        // never run side by side.
        val w = planner().plan(listOf(sched("1", "spawn_subagent"), sched("2", "spawn_subagent")))
        assertEquals("both spawns in one wave", 1, w.size)
        assertEquals(2, w[0].calls.size)
    }

    @Test
    fun `spawns do not split the file calls around them`() {
        val w = planner().plan(
            listOf(
                sched("1", "file_read", "/a"),
                sched("2", "spawn_subagent"),
                sched("3", "spawn_subagent"),
                sched("4", "file_write", "/b"),
            ),
        )
        assertEquals("one wave for the whole turn", 1, w.size)
        assertEquals(4, w[0].calls.size)
    }

    // ---- reactive DAG ([T-tool-scheduler-reactive]) ----

    @Test
    fun `reactive write waits only for the last conflicting reader`() {
        val nodes = planner().planReactive(listOf(
            sched("1", "file_read", "/a"),
            sched("2", "file_read", "/b"), // unrelated fast call
            sched("3", "file_write", "/a"),
        ))
        val deps = nodes.associate { it.call.id to it.dependsOn }
        assertEquals(emptySet<String>(), deps["1"])
        assertEquals(emptySet<String>(), deps["2"])
        // The write waits for the reader of ITS file only — a wave barrier
        // would have idled it behind the unrelated read of /b as well.
        assertEquals(setOf("1"), deps["3"])
    }

    @Test
    fun `reactive write-write keeps model order`() {
        val nodes = planner().planReactive(listOf(
            sched("1", "file_write", "/a"),
            sched("2", "file_write", "/a"),
        ))
        val deps = nodes.associate { it.call.id to it.dependsOn }
        assertEquals(setOf("1"), deps["2"])
    }

    @Test
    fun `reactive global is hermetic in both directions`() {
        val nodes = planner().planReactive(listOf(
            sched("1", "file_read", "/a"),
            sched("2", "shell_execute"),
            sched("3", "file_read", "/b"),
        ))
        val deps = nodes.associate { it.call.id to it.dependsOn }
        // The shell depends on everything earlier (hermetic). readB depends
        // on the SHELL only - which transitively covers readA. Direct deps
        // stay minimal: the graph is transitive by construction.
        assertEquals(setOf("1"), deps["2"])
        assertEquals(setOf("2"), deps["3"])
    }

    @Test
    fun `reactive spawns run parallel with no deps`() {
        val nodes = planner().planReactive(listOf(
            sched("1", "spawn_subagent"),
            sched("2", "spawn_subagent"),
        ))
        val deps = nodes.associate { it.call.id to it.dependsOn }
        assertEquals(emptySet<String>(), deps["1"])
        assertEquals(emptySet<String>(), deps["2"])
    }
}

/**
 * [PARALLEL_TOOL_ENGINE_SPEC] The hierarchy + reactive-DAG pins, driven
 * through the REAL platform extractor (jsonObjectResourceKeys) — the
 * integration seam the engine loop actually wires (ChatViewModel
 * injects exactly this function).
 */
class ToolSchedulerHierarchyTest {

    private fun realPlanner() = ToolScheduler(
        resourceKeysOf = ::jsonObjectResourceKeys,
        maxConcurrent = 8,
    )

    private fun call(id: String, name: String, argsJson: String = "{}") =
        ToolScheduler.SchedCall(id, name, argsJson)

    private fun fileRead(id: String, path: String) =
        call(id, "file_read", """{"path":"$path"}""")

    private fun fileWrite(id: String, path: String) =
        call(id, "file_write", """{"path":"$path"}""")

    private fun depsOf(nodes: List<ToolScheduler.ReactiveNode>, id: String) =
        nodes.first { it.call.id == id }.dependsOn

    @Test
    fun `the canonical research batch runs four calls immediately`() {
        // T1 web_search, T2 read a, T3 read b, T4 write a, T5 read c:
        // T1/T2/T3/T5 carry no deps at t=0; T4 depends ONLY on T2.
        val nodes = realPlanner().planReactive(
            listOf(
                call("T1", "web_search", """{"query":"q"}"""),
                fileRead("T2", "src/a.rs"),
                fileRead("T3", "src/b.rs"),
                fileWrite("T4", "src/a.rs"),
                fileRead("T5", "src/c.rs"),
            ),
        )
        assertEquals(emptySet<String>(), depsOf(nodes, "T1"))
        assertEquals(emptySet<String>(), depsOf(nodes, "T2"))
        assertEquals(emptySet<String>(), depsOf(nodes, "T3"))
        assertEquals(emptySet<String>(), depsOf(nodes, "T5"))
        assertEquals(setOf("T2"), depsOf(nodes, "T4"))
    }

    @Test
    fun `a write under a grepped tree waits for the tree read`() {
        val nodes = realPlanner().planReactive(
            listOf(
                call("g", "grep", """{"pattern":"x","path":"src"}"""),
                fileWrite("w", "src/main.rs"),
                fileRead("r", "docs/readme.md"),
            ),
        )
        assertEquals(setOf("g"), depsOf(nodes, "w"))
        assertEquals(emptySet<String>(), depsOf(nodes, "r"))
    }

    @Test
    fun `a write is blocked by ALL earlier readers of its file - not just the last`() {
        // [PARALLEL_TOOL_ENGINE_SPEC §4.2.3] WAR: both reads must finish
        // before the write; the old per-key lastUse map only remembered
        // the last one. Reads run in parallel, so the first read may
        // still be in flight when the last one returns.
        val nodes = realPlanner().planReactive(
            listOf(
                fileRead("r1", "a.rs"),
                fileRead("r2", "a.rs"),
                fileWrite("w", "a.rs"),
            ),
        )
        assertEquals(setOf("r1", "r2"), depsOf(nodes, "w"))
        // The wave plan is the same story: read wave, then the write.
        val waves = realPlanner().plan(
            listOf(fileRead("r1", "a.rs"), fileRead("r2", "a.rs"), fileWrite("w", "a.rs")),
        )
        assertEquals(2, waves.size)
        assertEquals(2, waves[0].calls.size)
    }

    @Test
    fun `tree reads and file reads coexist inside one tree`() {
        val waves = realPlanner().plan(
            listOf(
                call("g", "grep", """{"pattern":"x","path":"src"}"""),
                fileRead("r", "src/main.rs"),
                fileRead("r2", "src/other.rs"),
            ),
        )
        assertEquals(1, waves.size)
        assertEquals(3, waves[0].calls.size)
    }

    @Test
    fun `normalized paths collide across textual variants`() {
        val waves = realPlanner().plan(
            listOf(
                fileRead("r", "./src/a.rs"),
                fileWrite("w", "src/a.rs"),
            ),
        )
        assertEquals(2, waves.size)
    }

    @Test
    fun `sibling writes stay parallel - no directory over-serialization`() {
        // Two writes to DIFFERENT files in the same directory are
        // independent: the reference model's rule 2. The spec's
        // fs_parent key is realized as the hierarchy predicate, never
        // as a literal shared key (that would serialize siblings).
        val waves = realPlanner().plan(
            listOf(fileWrite("w1", "dir/a.rs"), fileWrite("w2", "dir/b.rs")),
        )
        assertEquals(1, waves.size)
    }

    @Test
    fun `tg reads of the same chat run in parallel`() {
        val waves = realPlanner().plan(
            listOf(
                call("t1", "tg_read", """{"chat_id":-100500}"""),
                call("t2", "tg_search", """{"chat_id":-100500,"query":"x"}"""),
                call("t3", "tg_read", """{"chat_id":-100600}"""),
            ),
        )
        assertEquals(1, waves.size)
        assertEquals(3, waves[0].calls.size)
    }

    @Test
    fun `a memory read orders against an earlier memory write`() {
        val nodes = realPlanner().planReactive(
            listOf(
                call("w", "memory_write", """{"text":"x"}"""),
                call("r", "memory_get", """{"key":"x"}"""),
                call("s", "web_search", """{"query":"q"}"""),
            ),
        )
        assertEquals(setOf("w"), depsOf(nodes, "r"))
        assertEquals(emptySet<String>(), depsOf(nodes, "s"))
    }

    @Test
    fun `two memory writes still serialize on the store key`() {
        val waves = realPlanner().plan(
            listOf(call("w1", "memory_write", "{}"), call("w2", "memory_write", "{}")),
        )
        assertEquals(2, waves.size)
    }

    @Test
    fun `a global shell still hermetically separates the reactive graph`() {
        val nodes = realPlanner().planReactive(
            listOf(
                fileRead("r1", "a.rs"),
                call("sh", "shell_execute", """{"command":"ls"}"""),
                fileRead("r2", "b.rs"),
            ),
        )
        assertEquals(setOf("r1"), depsOf(nodes, "sh"))
        assertEquals(setOf("r1", "sh"), depsOf(nodes, "r2"))
    }

    @Test
    fun `the overlap predicate follows the spec rules exactly`() {
        // Same key: always.
        assert(ToolScheduler.keysOverlap("fs:/a", "fs:/a"))
        // Tree contains file.
        assert(ToolScheduler.keysOverlap("fs_tree:/a", "fs:/a/b.rs"))
        // Deep tree ancestor.
        assert(ToolScheduler.keysOverlap("fs_tree:/a", "fs:/a/b/c.rs"))
        // Root tree covers all absolute files.
        assert(ToolScheduler.keysOverlap("fs_tree:/", "fs:/a.txt"))
        assert(!ToolScheduler.keysOverlap("fs_tree:/", "fs:a.txt"))
        // Relative dot tree covers relative files only.
        assert(ToolScheduler.keysOverlap("fs_tree:.", "fs:src/a.rs"))
        assert(!ToolScheduler.keysOverlap("fs_tree:.", "fs:/var/a.rs"))
        // Prefix must respect the separator: /a does NOT cover /ab.
        assert(!ToolScheduler.keysOverlap("fs_tree:/a", "fs:/ab.rs"))
        // Two trees never collide with each other (reads).
        assert(!ToolScheduler.keysOverlap("fs_tree:/a", "fs_tree:/b"))
        // Non-fs domains: exact equality only (never collides with fs_tree).
        assert(!ToolScheduler.keysOverlap("fs_tree:", "mem:store"))
        assert(!ToolScheduler.keysOverlap("fs_tree:", "net:search"))
        assert(!ToolScheduler.keysOverlap("net:search", "net:other"))
        assert(!ToolScheduler.keysOverlap("mem:store", "fs:/a"))
    }

    @Test
    fun `io pool covers the newly declared reads`() {
        assert(ToolConcurrencyClass.isIo("grep"))
        assert(ToolConcurrencyClass.isIo("web_search"))
        assert(ToolConcurrencyClass.isIo("memory_get"))
        assert(ToolConcurrencyClass.isIo("tg_read"))
        assert(ToolConcurrencyClass.isIo("tg_list_chats"))
        // shell/browser/spawn stay on the conservative CPU gate.
        assert(!ToolConcurrencyClass.isIo("shell_execute"))
        assert(!ToolConcurrencyClass.isIo("browser_use"))
        assert(!ToolConcurrencyClass.isIo("spawn_subagent"))
    }

    @Test
    fun `every tg tool is a read - the vault surface is read-only`() {
        for (name in listOf(
            "tg_read", "tg_search", "tg_list_chats", "tg_get_chat", "tg_unread",
            "tg_global_search", "tg_channel_digest", "tg_scan_channels", "tg_period_read",
            "tg_replies", "tg_stats", "tg_link_search", "tg_multi_search",
            "tg_download_media", "tg_archive_chat", "tg_read_parallel",
            "tg_read_new", "tg_read_chunked", "tg_search_parallel", "tg_analyze_chat",
            "tg_whoami",
        )) {
            assertEquals("$name must be READ", ToolScheduler.Effect.READ, ToolScheduler.effectOf(name))
        }
    }

    @Test
    fun `todo_write is a resource write now, not turn state`() {
        assertEquals(ToolScheduler.Effect.WRITE, ToolScheduler.effectOf("todo_write"))
        // The rest of the META family stays META.
        assertEquals(ToolScheduler.Effect.META, ToolScheduler.effectOf("ask_user"))
        assertEquals(ToolScheduler.Effect.META, ToolScheduler.effectOf("plan_submit"))
    }
}
