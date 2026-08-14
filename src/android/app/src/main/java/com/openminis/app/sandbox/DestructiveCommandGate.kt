package com.openminis.app.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the one destructive command that is waiting for the user's answer.
 *
 * WHY A SEPARATE OBJECT AND NOT A FIELD ON THE VIEW MODEL. The decision is
 * taken deep inside `executeShellCommand`, in a coroutine that must *suspend*
 * until the user taps a button in a dialog owned by Compose. A plain callback
 * would invert the control flow and leak the shell path into the UI layer;
 * a StateFlow plus a CompletableDeferred keeps the suspension where the
 * command is, and the UI merely observes and answers.
 *
 * ONE AT A TIME, BY DESIGN. The shell inside a session is serialised by
 * ExecutionCoordinator's per-session mutex, so a second destructive command
 * cannot arrive while the first is pending in the same session. A request from
 * *another* session while one is pending is rejected rather than queued: a
 * dialog stack in a chat app is unusable, and auto-denying is the safe
 * direction — the agent sees a refusal and can ask again.
 */
object DestructiveCommandGate {

    data class Request(
        val id: Long,
        val sessionId: String,
        /** Full command as the agent wrote it. Shown verbatim: the user must be
         *  able to see the glob that is about to expand. */
        val command: String,
        /** Human-readable reason from [DestructiveCommandPolicy]. */
        val reason: String,
        /** The specific fragment that triggered the prompt. */
        val fragment: String,
    )

    private val counter = AtomicLong(0)
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<Boolean>? = null

    /**
     * Ask the user. Suspends until [approve] or [deny] is called.
     * Returns false when another request is already pending — see the note on
     * auto-denial above.
     */
    suspend fun requestApproval(
        sessionId: String,
        command: String,
        reason: String,
        fragment: String,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (_pending.value != null) return false
            answer = deferred
            _pending.value = Request(
                id = counter.incrementAndGet(),
                sessionId = sessionId,
                command = command,
                reason = reason,
                fragment = fragment,
            )
        }
        return try {
            deferred.await()
        } finally {
            synchronized(this) {
                _pending.value = null
                answer = null
            }
        }
    }

    fun approve() {
        synchronized(this) { answer }?.complete(true)
    }

    fun deny() {
        synchronized(this) { answer }?.complete(false)
    }

    /** Called when the session is torn down mid-prompt: unblock the waiting
     *  command with a denial so its coroutine does not leak. */
    fun cancelAll() {
        synchronized(this) {
            answer?.complete(false)
            answer = null
            _pending.value = null
        }
    }
}
