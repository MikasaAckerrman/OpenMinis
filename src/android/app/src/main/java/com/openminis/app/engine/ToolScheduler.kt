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
) {

    enum class Effect { READ, WRITE, META, GLOBAL }

    data class SchedCall(
        val id: String,
        val name: String,
        val argsJson: String,
    )

    data class Wave(val calls: List<SchedCall>)

    companion object {
        /** Effect class per tool name; unknown -> GLOBAL (safe default). */
        fun effectOf(toolName: String): Effect = when (toolName) {
            "file_read", "read_image", "memory_get", "supermemory_search",
            "memory_blocks_view", "todo_read", "bg_check", "bg_list",
            "grep", "glob", "list_agents", "task_board", "await_result", "web_search", "webfetch",
            -> Effect.READ

            "file_write", "file_edit", "memory_write", "memory_blocks_edit",
            -> Effect.WRITE

            "todo_write", "ask_user", "plan_submit", "subagent_task",
            "bg_steer", "turn_timer",
            -> Effect.META

            else -> Effect.GLOBAL // shell_execute, bg_run, browser_use, mcp,
            // root_shell, spawn_*, run_graph, session_gc, anything unknown
        }

        /**
         * Tools whose resource keys the platform knows how to extract.
         * Everything else returns GLOBAL via resourceKeysOf injection
         * (undeclared = global lock).
         */
        val KEY_DECLARING_TOOLS: Set<String> = setOf(
            "file_read", "file_write", "file_edit", "read_image",
            "memory_write", "memory_blocks_edit",
        )
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
                val conflict = when (effect) {
                    Effect.READ -> keys.any { it in wWrite }
                    Effect.WRITE -> keys.any { it in wRead || it in wWrite }
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

    /**
     * [T-tool-scheduler-reactive] Dependency DAG for BARRIER-FREE execution
     * (the turbo path): a call starts the moment its conflicting
     * predecessors finish instead of waiting for a whole wave — the wave
     * plan is correct, but a wave BARRIER idles fast calls behind the
     * slowest member of the previous wave.
     *
     * Semantics are exactly plan()'s, expressed as per-call dependencies:
     *   - per key, model order is preserved against every conflicting use
     *     (a WRITE waits for the last reader OR writer of that key; a READ
     *     waits only for the last writer);
     *   - GLOBAL (undeclared) is hermetic in BOTH directions: it depends on
     *     all earlier calls and every later call depends on it;
     *   - spawn_* declares a unique key, so spawns stay parallel.
     *
     * Dependencies point BACKWARD only (model order), which lets the
     * executor launch every coroutine up front in list order and let each
     * one await just its own dependencies — no wave barrier, no idle time,
     * maxConcurrent enforced by a semaphore acquired AFTER the waits (a
     * waiting call holds no slot).
     */
    data class ReactiveNode(val call: SchedCall, val dependsOn: Set<String>)

    fun planReactive(calls: List<SchedCall>): List<ReactiveNode> {
        val nodes = mutableListOf<ReactiveNode>()
        // Per key: the last call that USED it (read or write) and the last
        // call that WROTE it. A write depends on the last use (order against
        // readers and writers); a read depends only on the last write.
        val lastUseBy = HashMap<String, String>()
        val lastWriteBy = HashMap<String, String>()
        var globalBarrier: String? = null
        var lastMeta: String? = null

        for (call in calls) {
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
                        if (effect == Effect.WRITE) lastUseBy[k]?.let { deps.add(it) }
                        lastWriteBy[k]?.let { deps.add(it) }
                    }
                }
                for (k in keys) {
                    lastUseBy[k] = call.id
                    if (effect == Effect.WRITE) lastWriteBy[k] = call.id
                }
            }
            nodes.add(ReactiveNode(call, deps))
        }
        return nodes
    }
}
