package com.openminis.app.data

/**
 * [T-no-compact-mid-request] When automatic context upkeep is allowed to run.
 *
 * ## The bug
 *
 * Maintenance was fired from `checkContextBeforeSend()`, which runs at the top
 * of `send()` — BEFORE `_isStreaming` is set — and `compactAll()` /
 * `rescueCompactNow()` do their work in a detached coroutine. So a compaction
 * could land while the turn it was supposed to help was already building its
 * request: the marker and summary changed under the request builder, the slice
 * boundary fell between an assistant `tool_use` and its `tool_result`, and the
 * provider answered
 *
 * ```
 * [400] TOOL_USE_RESULT_MISMATCH: unexpected `tool_use_id` found in
 * `tool_result` blocks … Each `tool_result` block must have a corresponding
 * `tool_use` block in the previous message.
 * ```
 *
 * The `_isStreaming` guards inside compactAll did not catch it because at that
 * moment the flag was still false.
 *
 * ## The rule
 *
 * Reshaping the payload is only safe when nothing is reading it. That means
 * BETWEEN turns, never inside one. So:
 *
 *  - a turn is in flight  → defer, remember it was wanted;
 *  - the turn just ended  → run the deferred work now, before the next send;
 *  - genuinely idle       → run immediately.
 *
 * The only thing still allowed mid-turn is the LOCAL offload pass, and only
 * because it rewrites a part's *content* into a stub in place — it never
 * changes the message list, so pairing cannot break.
 */
object MaintenanceWindow {

    enum class Decision {
        /** Safe to reshape the payload now. */
        RUN_NOW,

        /** A turn is reading the payload — remember and run when it ends. */
        DEFER,

        /** Nothing wanted. */
        SKIP,
    }

    /**
     * @param wanted whether the tier policy asked for a payload-reshaping pass
     * @param turnInFlight true while a request is being built or streamed
     * @param compactionInFlight true while a previous pass is still running
     */
    fun decide(
        wanted: Boolean,
        turnInFlight: Boolean,
        compactionInFlight: Boolean,
    ): Decision = when {
        !wanted -> Decision.SKIP
        compactionInFlight -> Decision.SKIP
        turnInFlight -> Decision.DEFER
        else -> Decision.RUN_NOW
    }

    /** True for the tiers that rewrite the message list (and so must wait). */
    fun reshapesPayload(action: ContextMaintenance.Action): Boolean =
        action == ContextMaintenance.Action.FULL ||
            action == ContextMaintenance.Action.RESCUE
}
