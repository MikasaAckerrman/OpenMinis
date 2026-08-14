package com.openminis.app.feedback

/**
 * [T-completion-haptics] How a turn ended, and whether that ending deserves a
 * buzz. Pure Kotlin on purpose — no Android imports — so the policy is
 * unit-testable and the Vibrator wrapper stays a thin shell around it.
 *
 * The distinction that matters: a turn that ENDED ON ITS OWN (finished or
 * failed) is news to the user — they may be in another app, or looking away,
 * and the whole point of the buzz is "come back, it's your move". A turn the
 * user CANCELLED is not news: they just pressed Stop, their thumb is on the
 * screen. Buzzing there would be the app telling the user what the user just
 * did.
 */
enum class TurnOutcome {
    /** Agent loop ran to completion (including any queued-prompt drain). */
    Completed,

    /** Ended on an error, all fallbacks exhausted. Still worth a buzz. */
    Failed,

    /** User pressed Stop. Deliberately silent. */
    Cancelled,
}

object CompletionFeedbackPolicy {

    /**
     * Whether to fire the completion haptic.
     *
     * [enabled] is the user's setting. [wasActive] guards against a session
     * that was never marked active reaching the teardown path — buzzing there
     * would mean a buzz with no turn behind it (the tracker already uses the
     * same guard for the notification listener).
     */
    fun shouldVibrate(
        outcome: TurnOutcome,
        enabled: Boolean,
        wasActive: Boolean = true,
    ): Boolean {
        if (!enabled) return false
        if (!wasActive) return false
        return when (outcome) {
            TurnOutcome.Completed, TurnOutcome.Failed -> true
            TurnOutcome.Cancelled -> false
        }
    }

    /**
     * The double pulse, as a `VibrationEffect.createWaveform` timing array:
     * (wait, buzz, gap, buzz) in milliseconds.
     *
     * Tuned rather than guessed: two 40 ms pulses read as one deliberate
     * "double tap" signal, distinct from the single ~20 ms tick Android uses
     * for keyboard/long-press. The 90 ms gap is the load-bearing number — under
     * ~60 ms the two pulses smear into one longer buzz on devices with a slow
     * LRA, and over ~150 ms they read as two unrelated events.
     */
    val DOUBLE_PULSE_TIMINGS: LongArray = longArrayOf(0L, 40L, 90L, 40L)

    /** Amplitudes paired with [DOUBLE_PULSE_TIMINGS]; used only when the
     *  device reports amplitude control. 0 for the waits, ~70% for the pulses —
     *  full 255 is startling for a passive notification. */
    val DOUBLE_PULSE_AMPLITUDES: IntArray = intArrayOf(0, 180, 0, 180)

    /** Total wall time of the pattern; used by the legacy pre-amplitude path. */
    val DOUBLE_PULSE_DURATION_MS: Long = DOUBLE_PULSE_TIMINGS.sum()
}
