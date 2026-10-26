package com.openminis.app.offload

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [T-stream-slot-claim] Claiming the run slot of a session.
 *
 * The slot is a single permit: one agent turn at a time. The check and the
 * claim have to be ONE atomic step.
 *
 * Why: the start paths read the flag, did their preparation, and only then
 * wrote it. The window between the read and the write was wide - 68 lines
 * in the tool-block rerun alone - and a message that arrived in that window
 * passed the same check. Two turns then ran at once and wrote into the same
 * transcript, which is what the user saw as two mixed-together works.
 *
 * A plain read-then-write cannot be closed: `compareAndSet` either takes
 * the slot or reports that somebody else already holds it, and only one
 * caller can win.
 */
fun MutableStateFlow<Boolean>.tryClaimStreamSlot(): Boolean = compareAndSet(false, true)

/**
 * Give the slot back. A start path that gives up before the run owns it
 * must call this, otherwise the session stays busy and every later turn is
 * rejected as "already streaming".
 */
fun MutableStateFlow<Boolean>.releaseStreamSlot() {
    value = false
}
