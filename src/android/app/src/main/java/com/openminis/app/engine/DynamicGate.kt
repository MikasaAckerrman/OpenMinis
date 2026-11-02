package com.openminis.app.engine

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * [B20-followup] A permit gate whose ceiling is RE-EVALUATED on every
 * acquisition — unlike kotlinx Semaphore, whose capacity is fixed at
 * construction. DeviceGuard shrinks the ceiling at runtime (thermal / low
 * RAM); this gate stops admitting NEW work within one poll interval while
 * NEVER cancelling or interrupting work that already holds a permit
 * (the invariant from the reviewer: снижение потолка зажимает только новые
 * запуски).
 *
 * Mechanics: an AtomicInteger active counter + a short poll loop. A caller
 * increments optimistically; over the ceiling it backs off (cancellable
 * delay) and retries. No waiter registry, no lost wakeups, no re-creation
 * hazard. The poll is bounded by the ceiling's own cadence — DeviceGuard
 * changes state rarely, so a 50 ms tick is invisible.
 */
class DynamicGate(
    /** Current ceiling, re-evaluated on every acquire attempt. */
    private val ceiling: () -> Int,
    /** Poll interval for over-ceiling waiting (ms). */
    private val pollMs: Long = 50,
) {
    constructor(ceiling: () -> Int) : this(ceiling, 50L)

    private val active = AtomicInteger(0)

    /** Non-suspending probe for refuse-style call sites. */
    fun tryAcquire(): Boolean {
        while (true) {
            val now = active.incrementAndGet()
            if (now <= ceiling().coerceAtLeast(1)) return true
            active.decrementAndGet()
            return false
        }
    }

    /** Release a permit taken by [tryAcquire]. */
    fun release() {
        active.decrementAndGet()
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (true) {
            if (tryAcquire()) break
            delay(pollMs)
        }
        try {
            return block()
        } finally {
            active.decrementAndGet()
        }
    }

    /** Current number of permit holders (diagnostics/tests). */
    fun activeCount(): Int = active.get()
}
