package com.openminis.app.sandbox

import com.openminis.app.engine.AgentConcurrency
import com.openminis.app.engine.DynamicGate

/**
 * [B51-proot-gate] A hard ceiling on CONCURRENT WORKER shells — the ptrace
 * storm bound. Every worker session's shell is a proot process (tracer) +
 * /bin/sh (tracee): every syscall the tracee makes costs a tracer context
 * switch. The vc162 AND vc166 incidents froze the whole device the same
 * way — the LLM parallel ceiling (up to 6 at thermal 0) admitted that many
 * worker sessions, each dragging its own ptrace pair onto the chip, while
 * timed-out members (D1's complement) left their pairs running.
 *
 * This gate bounds the worker side to 2 slots (1 at SEVERE+) BEFORE the LLM
 * slot is taken, so worst case is 2 worker pairs + the user's main shell +
 * the vault pair = 4 proots on the device, bounded regardless of the LLM
 * ceiling. main/vault/terminal shells deliberately BYPASS this gate: the
 * user's own session must never wait behind background workers — the exact
 * starvation the old whole-device freeze looked like from the outside.
 *
 * Slot lifecycle = the batch member's lifetime: acquired before the member
 * starts, released in its finally (complement to [AgentGraphRunner]'s D1
 * cleanup, which kills the shells themselves on ANY exit path). The wait is
 * cancellable (a DynamicGate poll): the member's own 180s ceiling bounds it,
 * and a timeout produces the usual honest Timeout section instead of an
 * unbounded stall.
 */
object PRootGate {

    /** Ceiling re-read on EVERY acquire attempt — thermal-reactive for free. */
    private val gate = DynamicGate { ceilingFor(AgentConcurrency.thermalStatus) }

    /**
     * 2 worker shells on a healthy chip, 1 under SEVERE throttle. Deliberately
     * NOT scaled by cores: the bound exists because ptrace cost is per-syscall
     * on EVERY core the pairs land on — a flagship gains nothing from a 6th
     * concurrent tracer pair except thermal escalation.
     */
    internal fun ceilingFor(thermalStatus: Int): Int =
        if (thermalStatus >= 4) 1 else 2

    /**
     * Acquire one worker slot, suspending (cancellable 50ms poll) until the
     * current ceiling admits. MUST be paired with exactly one [endWorkerSlot]
     * in a finally — the pair brackets the member's whole lifetime, so a
     * cancelled member releases through its own unwinding.
     */
    suspend fun beginWorkerSlot() {
        while (!gate.tryAcquire()) {
            kotlinx.coroutines.delay(50)
        }
    }

    /** Release one slot taken by [beginWorkerSlot]. */
    fun endWorkerSlot() {
        gate.release()
    }

    /** Current slot holders (diagnostics/tests). */
    fun activeCount(): Int = gate.activeCount()
}
