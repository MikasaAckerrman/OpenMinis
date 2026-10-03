package com.openminis.app.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-ask-user] Structured mid-task questions — the mobile answer to "the
 * user is nearby but busy": the agent suspends on a question with
 * predefined options; the user answers with ONE TAP (or free text) and the
 * choice lands in the tool result verbatim.
 *
 * Mirrors [DestructiveCommandGate]'s architecture on purpose: a
 * process-global StateFlow the Compose dialog observes + a
 * CompletableDeferred the tool coroutine awaits. The UI layer never knows
 * about tools; the tool layer never touches Activities.
 *
 * ONE AT A TIME, same reasoning as the destructive gate: a stack of
 * question dialogs in a chat app is unusable. A second concurrent request
 * resolves immediately with a refusal string — the agent is told to
 * continue with its best judgment instead of silently hanging.
 */
object AskUserGate {

    data class Option(
        val label: String,
        val description: String = "",
    )

    data class Request(
        val id: Long,
        val sessionId: String,
        val question: String,
        val options: List<Option>,
        val allowFreeText: Boolean,
    )

    private val counter = AtomicLong(0)
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var answer: CompletableDeferred<String>? = null

    /** "Question is occupied" — returned to the caller when another ask is pending. */
    const val OCCUPIED = "REFUSED: another question is already on screen — continue with your best judgment."

    /** Suspends until the user answers. Returns the answer string (never blank on a real answer). */
    suspend fun ask(
        sessionId: String,
        question: String,
        options: List<Option>,
        allowFreeText: Boolean = true,
    ): String {
        val deferred = CompletableDeferred<String>()
        synchronized(this) {
            if (_pending.value != null) return OCCUPIED
            answer = deferred
            _pending.value = Request(
                id = counter.incrementAndGet(),
                sessionId = sessionId,
                question = question,
                options = options,
                allowFreeText = allowFreeText,
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

    /** The user tapped an option chip (label is verbatim). */
    fun answerOption(label: String) {
        synchronized(this) { answer }?.complete("USER ANSWER: $label")
    }

    /** The user typed free text. */
    fun answerText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(this) { answer }?.complete("USER TEXT: $trimmed")
    }

    /** The user dismissed the question — honest signal, not a fake answer. */
    fun skip() {
        synchronized(this) { answer }?.complete("SKIPPED: the user closed the question — continue with your best judgment.")
    }

    /** Session teardown / turn cancelled: unblock the waiting coroutine. */
    fun cancelAll() {
        synchronized(this) {
            answer?.complete("CANCELLED: the question was interrupted (turn stopped).")
            answer = null
            _pending.value = null
        }
    }
}
