package com.openminis.app.engine


/**
 * [T-m7-batch-planner] The conflict-aware parallel-batch DECISION, lifted
 * out of ChatViewModel's inline block (M7 slice 1).
 *
 * Rule (unchanged semantics, now declarative and unit-tested):
 *  - a batch goes parallel ONLY IF every call is in the batch-safe set
 *    (no shared mutable state, per-file scoping verified per tool) AND
 *    no two calls target the SAME path;
 *  - a read+write or write+write on one path depends on order — the
 *    model's sequencing intent must be preserved, so a duplicate path
 *    demotes the whole batch to sequential;
 *  - calls without a target path (memory_get) cannot conflict;
 *  - shell/browser/bg/subagent tools NEVER join: shared session state,
 *    ordered side effects — mixed batches stay sequential.
 *
 * The engine stays pure Kotlin: the path extraction for one tool call is
 * INJECTED by the platform (the real JSONObject parse lives in the
 * adapter); tests inject fakes.
 */
class ToolBatchPlanner(
    /** Target path of one call ("" when the tool takes no path). */
    private val pathOf: (toolName: String, argsJson: String) -> String = { _, _ -> "" },
) {

    data class PendingToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    )

    companion object {
        /**
         * Batch-safe tools, by NAME (not by MutationKind — memory_write is
         * a WRITE but touches the journal, so the safe set is its own
         * declarative table next to the taxonomy).
         */
        val BATCH_SAFE_TOOLS: Set<String> = setOf(
            "file_read", "read_image", "memory_get",
            "file_write", "file_edit",
        )
    }

    /** The conflict key of one call: its target path ("" = cannot conflict). */
    fun conflictKeyOf(call: PendingToolCall): String =
        if (call.name == "memory_get") "" else pathOf(call.name, call.argsJson)

    /**
     * May the batch run concurrently? Single calls never enter the
     * parallel path (nothing to gain); the sequential branch is the
     * universal fallback.
     */
    fun canParallelize(calls: List<PendingToolCall>): Boolean {
        if (calls.size <= 1) return false
        if (calls.any { it.name !in BATCH_SAFE_TOOLS }) return false
        val keys = calls.map { conflictKeyOf(it) }.filter { it.isNotEmpty() }
        return keys.size == keys.distinct().size
    }
}
