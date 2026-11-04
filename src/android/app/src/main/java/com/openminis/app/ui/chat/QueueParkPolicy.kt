package com.openminis.app.ui.chat

/**
 * [T-recursion-guard] Pure park/kick decision for a message arriving while a
 * turn may be running (extracted verbatim from enqueuePrompt's guard —
 * behavior-identical; extraction is the test seam for the queue
 * acceptance scenarios A/C, see REVIEWS/evidence/20261104/queue-acceptance-spec.md).
 *
 * INVARIANT: park is UNCONDITIONAL — this function never routes back to
 * sendMessage (the sendMessage⇄enqueuePrompt recursion, 23k-frame SOF on
 * the iQOO, is unreachable by construction). Delivery is owned by the
 * winding-down tail's last-breath kick, the reload kick, or the
 * liveness kick scheduled for the idle race.
 */
object QueueParkPolicy {

    data class Decision(
        /** Always true today: a message that raced a turn-end parks in the queue. */
        val park: Boolean,
        /** Schedule the liveness kick: only for the IDLE race — a kick fired
         *  while a turn is streaming or while the cancel window holds the lock
         *  would poll or collide with the winding tail; those cases are
         *  delivered by the tail itself (last-breath / reload kick). */
        val scheduleLivenessKick: Boolean,
        val reason: String,
    )

    fun decide(streaming: Boolean, sessionLockHeld: Boolean): Decision = when {
        streaming -> Decision(
            park = true,
            scheduleLivenessKick = false,
            reason = "turn running — tail drain delivers",
        )
        sessionLockHeld -> Decision(
            park = true,
            scheduleLivenessKick = false,
            reason = "cancel window — session lock held by winding turn; last-breath kick delivers",
        )
        else -> Decision(
            park = true,
            scheduleLivenessKick = true,
            reason = "idle race — last kick may have seen an empty queue; liveness kick delivers",
        )
    }
}
