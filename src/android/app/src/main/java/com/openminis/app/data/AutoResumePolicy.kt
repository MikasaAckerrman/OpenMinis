package com.openminis.app.data

/**
 * [T-auto-resume] Should a turn that died on the network be resumed
 * automatically, and when?
 *
 * ## Why this exists
 *
 * The in-request retry ladder ([TransientRetryBudget]) already recovers most
 * network faults. The device journal (120 entries) measures it:
 *
 *   FAIL 48 · RETRY 48 · OK 21 · GIVEUP 3
 *
 * So 21 of 24 affected turns healed inside the request — the ladder works. The
 * remaining 3 all look the same: NO_RESPONSE, gorouter.app, `attempts=2`, i.e.
 * the budget ran out. At that point the turn simply dies and only a human tap on
 * Resume continues it. That tap is what this policy replaces.
 *
 * Deliberately NOT solved by widening the ladder: each NO_RESPONSE attempt costs
 * a full 120s watchdog, so more attempts inside one request means minutes of a
 * frozen screen. A resume is cheaper than a wait — it starts a FRESH request
 * (new socket, fresh headers) after a pause that lets the far side recover.
 *
 * ## What may be auto-resumed
 *
 * ONLY transport faults, and only when the model produced no answer. A turn that
 * failed because of the model's own decision (content filter), the account
 * (auth, quota), the request (400), or the user (cancel) must NOT be replayed:
 * retrying it burns tokens to reach the identical failure, and in the cancel
 * case it overrides an explicit human decision.
 *
 * Pure and clock-free so every branch is testable; the caller owns the timer, the
 * connectivity wait and the actual [resume] call.
 */
object AutoResumePolicy {

    /** Why a dead turn is (or is not) eligible for an automatic resume. */
    enum class Cause {
        /** TTFB watchdog: request written, server said nothing. */
        NO_RESPONSE,

        /** Gateway/relay 5xx — the node is switching upstreams. */
        BAD_GATEWAY,

        /** Socket-level failure (reaped pooled connection, reset). */
        CONNECTION,

        /** DNS / radio parked — no route to the host. */
        OFFLINE,

        /**
         * Anything else: provider 4xx, auth, quota, content filter, tool error,
         * user cancel, agent-turn ceiling. Never auto-resumed.
         */
        OTHER,
    }

    /** What the caller should do with a dead turn. */
    sealed interface Decision {
        /** Resume after [delaySec], counting this as attempt [attempt]. */
        data class Resume(val delaySec: Int, val attempt: Int) : Decision

        /** Do nothing; the user decides. [reason] is for the log, not the UI. */
        data class Stop(val reason: String) : Decision
    }

    /**
     * Max automatic resumes per turn.
     *
     * Three, because the observed outage shape is a node that recovers in tens of
     * seconds: with the ladder below, three attempts span ~3 minutes of real
     * waiting. If the endpoint is still mute after that, the fault is not a blip
     * and silently retrying forever would hide a real outage behind a spinner —
     * and keep spending tokens on every partial turn.
     */
    const val MAX_ATTEMPTS: Int = 3

    /**
     * Backoff before attempt [attempt] (0-based), in seconds: 15 · 45 · 120.
     *
     * Not the usual 1/2/4: those are tuned for a socket that fails instantly. A
     * gateway that swallowed a request and went silent has just cost 120s of
     * watchdog, so the far side needs time to finish whatever it is doing —
     * re-dialling one second later lands on the same unhealthy node. The ladder
     * grows steeply for the same reason a person would: if it did not come back
     * in 15s, it needs longer, not the same again.
     *
     * Out-of-range input is clamped rather than throwing: a policy that crashes
     * the error path would turn a recoverable failure into a lost turn.
     */
    fun delaySecForAttempt(attempt: Int): Int = when {
        attempt <= 0 -> 15
        attempt == 1 -> 45
        else -> 120
    }

    /**
     * Classify a failure message into a [Cause].
     *
     * Marker-based, matching how [TransientRetryBudget] classifies, and
     * deliberately not on bare digits: provider error text carries request ids
     * and model names, so `req_a502f3` must not read as a 502.
     *
     * @param isTransient the caller's own transient/fatal verdict. A message that
     *   looks transient but arrived as a hard provider error is NOT auto-resumed:
     *   the type is the authority, the text is only the detail. This is the same
     *   trap that made `is5xx` never fire (it checked ProviderError while
     *   mapHttpError produced TransientError).
     */
    fun classify(message: String?, isTransient: Boolean): Cause {
        if (!isTransient) return Cause.OTHER
        val m = message?.lowercase() ?: return Cause.OTHER
        return when {
            // Checked first: the TTFB marker is the most specific, and a stale
            // socket also matches the CONNECTION vocabulary below.
            m.contains(StaleConnectionPolicy.STALE_MARKER.lowercase()) -> Cause.NO_RESPONSE
            m.contains("unable to resolve host") ||
                m.contains("no address associated") -> Cause.OFFLINE
            m.contains("[502]") || m.contains("http 502") ||
                m.contains("[503]") || m.contains("http 503") ||
                m.contains("[504]") || m.contains("http 504") -> Cause.BAD_GATEWAY
            m.contains("connection closed") || m.contains("connection reset") ||
                m.contains("unexpected end of stream") ||
                m.contains("software caused connection abort") -> Cause.CONNECTION
            else -> Cause.OTHER
        }
    }

    /**
     * The decision itself.
     *
     * @param cause see [classify].
     * @param attemptsUsed automatic resumes already spent on THIS turn.
     * @param userCancelled the human stopped this turn. Vetoes everything: an
     *   automatic resume would override an explicit decision, which is worse than
     *   any error.
     * @param userSentNewMessage the human moved on. Resuming a superseded turn
     *   would interleave two conversations.
     * @param hasPartialAnswer whether the dead turn left text/tool output behind.
     *   Does NOT gate the decision — [resume] continues from a partial answer just
     *   as well as from an empty one — but is recorded here so callers cannot
     *   invent their own rule about it.
     */
    fun decide(
        cause: Cause,
        attemptsUsed: Int,
        userCancelled: Boolean,
        userSentNewMessage: Boolean,
        @Suppress("UNUSED_PARAMETER") hasPartialAnswer: Boolean = false,
    ): Decision {
        if (userCancelled) return Decision.Stop("user cancelled the turn")
        if (userSentNewMessage) return Decision.Stop("user already sent a new message")
        if (cause == Cause.OTHER) return Decision.Stop("not a transport failure")
        if (attemptsUsed >= MAX_ATTEMPTS) {
            return Decision.Stop("auto-resume budget spent ($attemptsUsed/$MAX_ATTEMPTS)")
        }
        return Decision.Resume(
            delaySec = delaySecForAttempt(attemptsUsed),
            attempt = attemptsUsed + 1,
        )
    }

    /**
     * Whether the caller must wait for real connectivity before resuming.
     *
     * Only [Cause.OFFLINE] means "there is no route". For the other causes the
     * network is up and the far side is at fault, so gating on connectivity would
     * add a pointless wait — the journal shows every one of those failures with
     * `net=on`.
     */
    fun awaitsConnectivity(cause: Cause): Boolean = cause == Cause.OFFLINE
}
