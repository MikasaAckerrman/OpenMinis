package com.openminis.app.engine

/**
 * [T-tool-scheduler] Wave-based, resource-keyed tool execution planner.
 *
 * Design (user spec 08.10): parallelism is a property of the ORCHESTRATOR,
 * not the model — the model proposes tool_calls, the engine decides what
 * runs concurrently. Rules:
 *
 *   read + read                -> parallel (same resource is fine)
 *   read + write on a resource -> serialize (model order preserved)
 *   write + write on a resource -> strict sequential (model order)
 *   write + write, disjoint resources -> parallel
 *   GLOBAL (shell/browser/bg/spawn/undeclared) -> its own wave, always
 *   META (user/turn state) -> one per wave
 *
 * A call without a declared resource-key set is GLOBAL: the spec says an
 * undeclared tool must take the global lock rather than silently ride a
 * parallel wave.
 *
 * Waves preserve the model's ordering intent for CONFLICTING pairs only;
 * independent calls may join an earlier wave (no ordering semantics
 * exists between independent calls — parallel is safe regardless of
 * position). Execution runs each wave under a maxConcurrent semaphore.
 */
class ToolScheduler(
    /**
     * Resource keys of one call (e.g. {"fs:/data/x.txt"}, {"memory:journal"}).
     * null = the tool did not declare keys -> GLOBAL lock (conservative).
     */
    private val resourceKeysOf: (toolName: String, argsJson: String) -> Set<String>? = { _, _ -> null },
    /** Hard cap on concurrently running tools inside one wave. */
    private val maxConcurrent: Int = 4,
    /**
     * [P1-io-concurrency] Separate cap for disk/network tools
     * ([ToolConcurrencyClass.IO_TOOLS]): EngineAgentLoop acquires from
     * this gate for I/O tools and from [maxConcurrent] for the rest — a
     * 10-way file_write batch to 10 distinct paths must not wait behind
     * the subagent ceiling.
     */
    private val ioMaxConcurrent: Int = 4,
) {

    enum class Effect { READ, WRITE, META, GLOBAL }

    data class SchedCall(
        val id: String,
        val name: String,
        val argsJson: String,
    )

    data class Wave(val calls: List<SchedCall>)

    companion object {
        /**
         * Effect class per tool name; unknown -> GLOBAL (safe default).
         * [PARALLEL_TOOL_ENGINE_SPEC §3.1] adds the tg_* MCP surface as
         * READ (all 21 vault tools are read-only by the iron rule — a
         * future account-mutating tg tool must opt OUT of this prefix
         * rule explicitly) and moves todo_write from META to WRITE:
         * it is a resource write on `mem:store`, not turn state.
         */
        fun effectOf(toolName: String): Effect = when {
            toolName.startsWith("tg_") -> Effect.READ

            toolName in setOf(
                "file_read", "read_image", "memory_get", "supermemory_search",
                "memory_blocks_view", "todo_read", "bg_check", "bg_list",
                "grep", "glob", "list_agents", "task_board", "await_result",
                "web_search", "webfetch",
            ) -> Effect.READ

            toolName in setOf(
                "file_write", "file_edit", "memory_write", "memory_blocks_edit",
                "todo_write",
            ) -> Effect.WRITE

            // [B47-shell-parallel] A shell can write anything, but its
            // command text now DECLARES its file surface
            // (jsonObjectResourceKeys -> shellResourceKeys): independent
            // commands run parallel under the CPU gate, while a shell and
            // a file tool naming the same extracted path still serialize.
            // The old undeclared GLOBAL lock serialized every shell
            // against everything — the device report's "one tool works,
            // the others never even start".
            toolName == "shell_execute" -> Effect.WRITE

            toolName in setOf(
                "ask_user", "plan_submit", "subagent_task",
                "bg_steer", "turn_timer",
            ) -> Effect.META

            else -> Effect.GLOBAL // bg_run, browser_use, mcp,
            // root_shell, spawn_*, run_graph, session_gc, anything unknown
        }

        /**
         * Tools whose resource keys the platform knows how to extract
         * (see jsonObjectResourceKeys for the full taxonomy).
         * Everything else returns GLOBAL via resourceKeysOf injection
         * (undeclared = global lock). The tg_* MCP surface is covered
         * by PREFIX: every tool the vault serves (tg_read, tg_search,
         * tg_multi_search, ...) declares mcp:tg:* keys.
         */
        val KEY_DECLARING_TOOLS: Set<String> = setOf(
            "file_read", "file_write", "file_edit", "read_image",
            // [PARALLEL_TOOL_ENGINE_SPEC] tree reads — a whole-tree read
            // genuinely races any write under that tree, and reads among
            // themselves stay parallel.
            "grep", "glob",
            // network reads: one shared key for searches (pure reads),
            // per-url for the idempotent GET. A missing url stays
            // UNDECLARED -> GLOBAL lock (never an empty key).
            "web_search", "webfetch",
            // the memory tier: one shared store key, reads and writes.
            "memory_get", "supermemory_search", "memory_blocks_view",
            "todo_read", "memory_write", "memory_blocks_edit", "todo_write",
            // [B47-shell-parallel] the command text declares its file
            // surface (shellResourceKeys) — possibly an EMPTY set.
            "shell_execute",
            // + every "tg_*" name (prefix rule in the extractor).
        )

        /**
         * [PARALLEL_TOOL_ENGINE_SPEC §3.1] The ONE cross-key conflict
         * rule: a filesystem tree key overlaps a file key when the file
         * lives inside the tree. Everything outside the fs/fs_tree pair
         * (net, mcp, mem, agent-spawn) collides only on exact equality.
         * Reads never conflict through this predicate among themselves —
         * it is applied against the held WRITE side for readers and
         * against BOTH held sides for writers.
         */
        fun keysOverlap(a: String, b: String): Boolean {
            if (a == b) return true
            val aTree = a.startsWith("fs_tree:")
            val bTree = b.startsWith("fs_tree:")
            if (!aTree && !bTree) return false
            val (dir, file) = when {
                aTree && !bTree -> {
                    if (!b.startsWith("fs:")) return false
                    a.removePrefix("fs_tree:") to b.removePrefix("fs:")
                }
                bTree && !aTree -> {
                    if (!a.startsWith("fs:")) return false
                    b.removePrefix("fs_tree:") to a.removePrefix("fs:")
                }
                else -> return false // two trees, different roots: reads coexist
            }
            val dirNorm = dir.trimEnd('/')
            if (dirNorm.isEmpty() || dirNorm == ".") {
                return if (dirNorm == ".") !file.startsWith("/")
                else file.startsWith("/") || dir.isEmpty()
            }
            return file.startsWith(dirNorm) &&
                (file.length == dirNorm.length || file[dirNorm.length] == '/')
        }
    }

    /**
     * Plan the batch into execution waves. Single-call batches collapse to
     * one wave (nothing to gain). Conflict order is the model order: a
     * conflicting pair keeps its relative list order across waves.
     */
    fun plan(calls: List<SchedCall>): List<Wave> {
        if (calls.isEmpty()) return emptyList()
        val waves = mutableListOf<MutableList<SchedCall>>()
        // Per open wave: keys currently held by reads, keys held by writes,
        // whether a META sits in it, and whether it is SEALED (a GLOBAL
        // call's wave accepts no further members: an undeclared write may
        // touch any resource, so nothing may ride alongside it).
        val waveReadKeys = mutableListOf<MutableSet<String>>()
        val waveWriteKeys = mutableListOf<MutableSet<String>>()
        val waveHasMeta = mutableListOf<Boolean>()
        val waveSealed = mutableListOf<Boolean>()

        fun openWave(): Int {
            waves.add(mutableListOf())
            waveReadKeys.add(mutableSetOf())
            waveWriteKeys.add(mutableSetOf())
            waveHasMeta.add(false)
            waveSealed.add(false)
            return waves.size - 1
        }

        for ((index, call) in calls.withIndex()) {
            val declaredEffect = effectOf(call.name)
            val declaredKeys = resourceKeysOf(call.name, call.argsJson)
            // [T-parallel-agents] A spawn starts an agent and touches no
            // shared resource. It used to be an undeclared (GLOBAL) call, and
            // an undeclared call gets a sealed wave of its own, so two spawns
            // in one turn never ran side by side - the whole point of asking
            // for several agents at once. Each spawn now declares its own
            // key, so the scheduler can put several of them into one wave
            // and run them concurrently.
            val effect: Effect = if (declaredKeys == null && call.name.startsWith("spawn_")) {
                Effect.WRITE
            } else declaredEffect
            val keys: Set<String>? = if (declaredKeys == null && call.name.startsWith("spawn_")) {
                setOf("agent-spawn:$index")
            } else declaredKeys
            if (keys == null) {
                // Undeclared resource: GLOBAL — a fresh, SEALED wave alone.
                val w = openWave()
                waves[w].add(call)
                waveSealed[w] = true
                continue
            }
            var placed = false
            for (w in waves.indices) {
                if (waveSealed[w]) continue
                if (waveHasMeta[w] && effect == Effect.META) continue
                val wRead = waveReadKeys[w]
                val wWrite = waveWriteKeys[w]
                // [PARALLEL_TOOL_ENGINE_SPEC §3.2] Hierarchical conflict:
                // a tree READ blocks only a WRITE under that tree, a file
                // WRITE blocks every tree READ of its ancestors — exact
                // equality stays the rule outside the fs/fs_tree pair.
                val conflict = when (effect) {
                    Effect.READ -> keys.any { k -> wWrite.any { keysOverlap(k, it) } }
                    Effect.WRITE -> keys.any { k ->
                        wWrite.any { keysOverlap(k, it) } || wRead.any { keysOverlap(k, it) }
                    }
                    Effect.META -> waveHasMeta[w]
                    Effect.GLOBAL -> true // unreachable: handled above
                }
                if (conflict) continue
                waves[w].add(call)
                when (effect) {
                    Effect.READ -> wRead.addAll(keys)
                    Effect.WRITE -> wWrite.addAll(keys)
                    Effect.META -> waveHasMeta[w] = true
                    Effect.GLOBAL -> Unit
                }
                placed = true
                break
            }
            if (!placed) {
                val w = openWave()
                waves[w].add(call)
                when (effect) {
                    Effect.READ -> waveReadKeys[w].addAll(keys)
                    Effect.WRITE -> waveWriteKeys[w].addAll(keys)
                    Effect.META -> waveHasMeta[w] = true
                    Effect.GLOBAL -> waveSealed[w] = true
                }
            }
        }
        return waves.filter { it.isNotEmpty() }.map { Wave(it) }
    }

    /** Concurrency cap for wave execution (>= 1). */
    fun cap(): Int = maxConcurrent.coerceAtLeast(1)

    /** [P1-io-concurrency] Concurrency cap for I/O-class tools (>= 1). */
    fun ioCap(): Int = ioMaxConcurrent.coerceAtLeast(1)

    /**
     * [T-tool-scheduler-reactive] Dependency DAG for BARRIER-FREE execution
     * (the turbo path): a call starts the moment its conflicting
     * predecessors finish instead of waiting for a whole wave — the wave
     * plan is correct, but a wave BARRIER idles fast calls behind the
     * slowest member of the previous wave.
     *
     * Semantics are exactly plan()'s, expressed as per-call dependencies
     * ([PARALLEL_TOOL_ENGINE_SPEC §4.2]):
     *   - a WRITE waits for EVERY earlier overlapping use (read or write —
     *     the WAR/WAW rules; parallel readers all finish-before the write,
     *     not just the last one by model order);
     *   - a READ waits only for earlier overlapping WRITES (RAW; reads run
     *     in parallel among themselves, including overlapping tree reads);
     *   - overlap = exact equality outside the fs/fs_tree pair,
     *     [keysOverlap]'s prefix rule inside it;
     *   - GLOBAL (undeclared) is hermetic in BOTH directions: it depends on
     *     all earlier calls and every later call depends on it;
     *   - spawn_* declares a unique key, so spawns stay parallel.
     *
     * Dependencies point BACKWARD only (model order), which lets the
     * executor launch every coroutine up front in list order and let each
     * one await just its own dependencies — no wave barrier, no idle time,
     * maxConcurrent enforced by a semaphore acquired AFTER the waits (a
     * waiting call holds no slot). The per-key scan is O(N) per call —
     * N <= ~20 calls per turn makes the whole DAG build microseconds.
     */
    data class ReactiveNode(val call: SchedCall, val dependsOn: Set<String>)

    /** One recorded key use: which call touched which key, and whether
     *  the touch was a write. The WAR rule needs ALL earlier readers of
     *  a resource, so a flat append-only log beats per-key "last" maps. */
    private data class KeyUse(val key: String, val id: String, val wrote: Boolean)

    /**
     * [PARALLEL_TOOL_ENGINE_SPEC Опора 1] The DAG builder as an ONLINE
     * object: one call in, its node out. planReactive() folds a finished
     * list through this; the streaming dispatch in EngineAgentLoop adds
     * calls the moment the provider's ToolCall event lands (the args
     * JSON closed) and launches each node while the model is still
     * generating. ONE dependency algorithm for both paths — a semantic
     * divergence between batch and streaming planning would be a race
     * in waiting.
     */
    inner class ReactiveDagBuilder internal constructor() {
        private val uses = mutableListOf<KeyUse>()
        private var globalBarrier: String? = null
        private var lastMeta: String? = null
        private val nodes = mutableListOf<ReactiveNode>()

        val size: Int get() = nodes.size

        fun add(call: SchedCall): ReactiveNode {
            val declaredKeys = resourceKeysOf(call.name, call.argsJson)
            val isSpawn = declaredKeys == null && call.name.startsWith("spawn_")
            val effect = if (isSpawn) Effect.WRITE else effectOf(call.name)
            val keys: Set<String>? = if (isSpawn) setOf("agent-spawn:${nodes.size}") else declaredKeys

            val deps = mutableSetOf<String>()
            if (keys == null) {
                // GLOBAL: hermetic — waits for EVERYTHING earlier, and every
                // later call waits for it (runs alone, both directions).
                for (earlier in nodes) deps.add(earlier.call.id)
                globalBarrier = call.id
            } else {
                globalBarrier?.let { deps.add(it) }
                if (effect == Effect.META) {
                    lastMeta?.let { deps.add(it) }
                    lastMeta = call.id
                } else {
                    for (k in keys) {
                        when {
                            effect == Effect.WRITE ->
                                uses.filter { keysOverlap(k, it.key) }.forEach { deps.add(it.id) }
                            else ->
                                uses.filter { it.wrote && keysOverlap(k, it.key) }
                                    .forEach { deps.add(it.id) }
                        }
                    }
                    for (k in keys) uses.add(KeyUse(k, call.id, effect == Effect.WRITE))
                }
            }
            val node = ReactiveNode(call, deps)
            nodes.add(node)
            return node
        }

        fun build(): List<ReactiveNode> = nodes.toList()
    }

    fun planReactive(calls: List<SchedCall>): List<ReactiveNode> {
        val builder = ReactiveDagBuilder()
        calls.forEach { builder.add(it) }
        return builder.build()
    }
}
