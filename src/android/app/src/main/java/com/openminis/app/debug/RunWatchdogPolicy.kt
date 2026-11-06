package com.openminis.app.debug

/**
 * [B13-idle-watchdog] Pure timing policy for HeadlessChatRunner's shared
 * wait loop. Replaces ScheduledAgentRunner's flat 10-minute ceiling with
 * two independent thresholds:
 *
 *  - IDLE: how long a turn may go WITHOUT a liveness pulse before it is
 *    declared dead (hung provider socket, crashed engine). A live turn
 *    pulses on every stream chunk, every message-list mutation, and every
 *    tool heartbeat — [com.openminis.app.ui.chat.ChatViewModel] bumps its
 *    toolHeartbeat counter inside executeTool, covering silent tool runs
 *    (a build, a test sweep) whose dispatch→result gap produces no
 *    message changes at all. The model-response wait (TTFT) deliberately
 *    does NOT pulse: a socket that never delivers the next chunk is
 *    exactly the stall this threshold exists to catch.
 *  - MAX RUN: the absolute ceiling. A turn can be alive-but-endless
 *    (runaway agent loop, tool that never returns); no pulse pattern
 *    rescues it past this line.
 *
 * Pure JVM — arithmetic over caller-supplied clock deltas, no clocks, no
 * coroutines — so the boundary semantics are pinned by tests:
 *   - ">=" counts as breached (a window measured at exactly the threshold
 *     is over, not within);
 *   - when both ceilings breach at once, MAX_RUN wins the report (the run
 *     is terminally over; the idle breach is implied by it);
 *   - idleMs == null restores the legacy flat behavior: silence alone
 *     never aborts, only the max ceiling does.
 */
object RunWatchdogPolicy {

    enum class Reason { IDLE, MAX_RUN }

    sealed interface Verdict {
        object Continue : Verdict
        data class Abort(val reason: Reason) : Verdict
    }

    fun evaluate(
        elapsedRunMs: Long,
        elapsedSilentMs: Long,
        idleMs: Long?,
        maxRunMs: Long,
    ): Verdict {
        if (elapsedRunMs >= maxRunMs) return Verdict.Abort(Reason.MAX_RUN)
        if (idleMs != null && elapsedSilentMs >= idleMs) return Verdict.Abort(Reason.IDLE)
        return Verdict.Continue
    }
}
