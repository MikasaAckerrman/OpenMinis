package com.openminis.app.data

/**
 * [T-partial-turn-durability] Write cadence for the in-flight assistant turn.
 *
 * ## Why this exists
 *
 * A streamed reply existed ONLY in memory until the turn completed
 * (`persistAssistantTurn` runs at round end). Any mid-round termination —
 * provider error, user Stop, OOM, process death — left the generated text
 * un-persisted: a 5-minute reasoning phase followed by a 15k-line answer was
 * lost whole because one transport error fired before the round closed.
 *
 * The durability layer has two halves (see [StreamHeartbeat]):
 *  - graceful termination (error / cancel): the loop's exported in-memory
 *    turn text is persisted directly — complete, no cadence loss;
 *  - hard termination (crash / process death): an append-only FILE journal
 *    carries the text, so at most one heartbeat interval is lost.
 *
 * The journal deliberately does NOT touch the DB while streaming: rewriting a
 * growing row per heartbeat is O(n²) cumulative writes and hammers SQLite +
 * flash. File appends are O(delta); the DB sees ONE insert at finalize.
 *
 * This object is the pure cadence policy — no Android, no I/O. Unit-tested.
 */
object StreamDurability {

    /**
     * Don't start a journal for tiny outputs: a file is only worth its writes
     * once there is real content to lose. Below this length the graceful
     * finalize paths still persist the text directly from memory.
     */
    const val FIRST_WRITE_CHARS = 512

    /** Minimum NEW chars between journal appends (in addition to time). */
    const val MIN_DELTA_CHARS = 1_024

    /**
     * Time between journal appends, scaled by total length: the append itself
     * is cheap, but the escape + write still costs flash wear, so huge turns
     * journal less often. At the coarsest tier a hard crash still loses at
     * most ~15s of tail — vs. everything before this fix.
     */
    fun heartbeatIntervalMs(len: Int): Long = when {
        len < 64_000 -> 5_000L
        len < 512_000 -> 10_000L
        else -> 15_000L
    }

    /**
     * @param nowMs current wall clock.
     * @param lastMs wall clock of the last accepted append (0 = never).
     * @param len total chars of the turn text now.
     * @param lastLen total chars at the last accepted append (0 = never).
     */
    fun shouldHeartbeat(nowMs: Long, lastMs: Long, len: Int, lastLen: Int): Boolean {
        if (len < FIRST_WRITE_CHARS) return false
        if (lastLen == 0) return true // first append once the threshold is crossed
        if (len <= lastLen) return false // text never shrinks; ignore stale snapshots
        return (nowMs - lastMs) >= heartbeatIntervalMs(len) && (len - lastLen) >= MIN_DELTA_CHARS
    }
}
