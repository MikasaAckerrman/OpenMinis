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
            "grep", "glob", "list_agents", "task_board", "web_search", "webfetch",
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
        // whether a META sits in it.
        val waveReadKeys = mutableListOf<MutableSet<String>>()
        val waveWriteKeys = mutableListOf<MutableSet<String>>()
        val waveHasMeta = mutableListOf<Boolean>()

        fun openWave() {
            waves.add(mutableListOf())
            waveReadKeys.add(mutableSetOf())
            waveWriteKeys.add(mutableSetOf())
            waveHasMeta.add(false)
        }

        for (call in calls) {
            val effect = effectOf(call.name)
            val keys = resourceKeysOf(call.name, call.argsJson) ?: run {
                // Undeclared resource: GLOBAL — always a fresh wave alone.
                openWave()
                waves.last().add(call)
                continue
            }
            var placed = false
            for (w in waves.indices) {
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
                openWave()
                waves.last().add(call)
                when (effect) {
                    Effect.READ -> waveReadKeys.last().addAll(keys)
                    Effect.WRITE -> waveWriteKeys.last().addAll(keys)
                    Effect.META -> waveHasMeta[waveHasMeta.size - 1] = true
                    Effect.GLOBAL -> Unit
                }
            }
        }
        return waves.filter { it.calls.isNotEmpty() }.map { Wave(it.calls) }
    }

    /** Concurrency cap for wave execution (>= 1). */
    fun cap(): Int = maxConcurrent.coerceAtLeast(1)
}
