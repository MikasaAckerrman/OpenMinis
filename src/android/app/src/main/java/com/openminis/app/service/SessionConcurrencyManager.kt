package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

/**
 * Limits concurrent agent-loop sessions to [MAX_CONCURRENT]. Excess sessions
 * wait in the coroutine semaphore's FIFO queue until a slot frees up.
 *
 * The semaphore is cancellation-safe: cancelling a session while it waits does
 * not consume a permit. StateFlow snapshots exist only for UI/diagnostics and
 * are mutated under [stateLock], never used as the admission primitive.
 */
object SessionConcurrencyManager {
    /** Product contract: at least ten independent sessions run concurrently. */
    const val MAX_CONCURRENT = 10

    private val stateLock = Any()
    @Volatile
    private var semaphore = Semaphore(MAX_CONCURRENT)

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    suspend fun acquireSlot(sessionId: String) {
        val gate = semaphore
        val queued = gate.availablePermits == 0
        if (queued) {
            synchronized(stateLock) {
                if (sessionId !in _suspendedSessions.value) {
                    _suspendedSessions.value = _suspendedSessions.value + sessionId
                }
            }
        }

        try {
            gate.acquire()
        } catch (t: Throwable) {
            synchronized(stateLock) {
                _suspendedSessions.value = _suspendedSessions.value.filterNot { it == sessionId }
            }
            throw t
        }

        synchronized(stateLock) {
            _suspendedSessions.value = _suspendedSessions.value.filterNot { it == sessionId }
            _runningSessions.value = _runningSessions.value + sessionId
        }
    }

    /** Acquire a slot for [block] and always release it afterwards. */
    suspend fun <T> withSlot(sessionId: String, block: suspend () -> T): T {
        acquireSlot(sessionId)
        return try {
            block()
        } finally {
            releaseSlot(sessionId)
        }
    }

    fun releaseSlot(sessionId: String) {
        val wasRunning = synchronized(stateLock) {
            if (sessionId !in _runningSessions.value) {
                false
            } else {
                _runningSessions.value = _runningSessions.value - sessionId
                true
            }
        }
        if (wasRunning) semaphore.release()
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value

    /** Test-only reset; callers must first finish/cancel all test coroutines. */
    internal fun resetForTest() {
        synchronized(stateLock) {
            semaphore = Semaphore(MAX_CONCURRENT)
            _runningSessions.value = emptySet()
            _suspendedSessions.value = emptyList()
        }
    }
}
