package com.openminis.app.data

/**
 * [T-offline-retry-budget] How many auto-retries a transient failure gets, by
 * error class.
 *
 * The turn used to get one fixed ladder (1s, 2s, 4s) for every transient error.
 * That is right for a 502 and wrong for the two failures the user actually hits
 * on a phone with mobile-only data:
 *
 *  - `Unable to resolve host "gorouter.app": No address associated with
 *    hostname` — the request never left the device. The radio was parked by
 *    Doze or the link was mid-handover. Seven seconds of ladder is not enough
 *    for a handover, so all three attempts burn while offline and the turn dies
 *    with an error that says nothing about the real cause.
 *  - `connection closed` / stream reset — a pooled h2 socket was reaped by a
 *    NAT or proxy. Eviction plus one more attempt normally fixes it, but if the
 *    ladder was already half spent on an earlier hiccup the turn dies instead.
 *
 * So the budget is per class, and offline failures also get a real wait for
 * connectivity instead of a fixed sleep. Bounded in both directions: a genuinely
 * offline device must still fail in finite time rather than hang forever.
 */
object TransientRetryBudget {

    enum class Kind {
        /** Name resolution failed — device had no usable network. */
        OFFLINE,

        /** Socket died under us (pool reap, stream reset, abrupt close). */
        CONNECTION,

        /** Everything else transient: 5xx, generic network error. */
        GENERIC,
    }

    private val CONNECTION_MARKERS = listOf(
        "connection closed",
        "connection reset",
        "stream was reset",
        "goaway",
        "unexpected end of stream",
        "broken pipe",
        "no response from server",
        "software caused connection abort",
    )

    fun classify(message: String?): Kind = when {
        DnsFailurePolicy.isNameResolutionFailure(message) -> Kind.OFFLINE
        message != null && CONNECTION_MARKERS.any { message.lowercase().contains(it) } -> Kind.CONNECTION
        else -> Kind.GENERIC
    }

    /** Total attempts allowed for [kind] before the turn is failed. */
    fun maxAttempts(kind: Kind): Int = when (kind) {
        // An offline stretch is measured in tens of seconds, not seconds. Each
        // attempt additionally waits for connectivity, so this is a time budget
        // of roughly 6 × up-to-20s worst case — long enough for a handover or a
        // lift ride, short enough to still answer the user.
        Kind.OFFLINE -> 6
        // Evict-and-retry usually succeeds on the first repeat; a couple of
        // spares cover a proxy reaping several pooled sockets in a row.
        Kind.CONNECTION -> 4
        Kind.GENERIC -> 3
    }

    /**
     * Backoff before attempt [attempt] (0-based) for [kind], in seconds.
     *
     * OFFLINE stays short because the real waiting is done by the connectivity
     * await that follows — sleeping here on top of that would only add dead
     * time. CONNECTION/GENERIC keep the familiar 1/2/4 shape, extended by
     * repeating the last step rather than growing without bound.
     */
    fun delaySecForAttempt(kind: Kind, attempt: Int): Int {
        if (attempt < 0) return 0
        return when (kind) {
            Kind.OFFLINE -> 1
            else -> when (attempt) {
                0 -> 1
                1 -> 2
                else -> 4
            }
        }
    }

    /** True when this class should wait for real connectivity before retrying. */
    fun awaitsConnectivity(kind: Kind): Boolean = kind == Kind.OFFLINE

    /**
     * True when the pooled sockets must be dropped before retrying. Reusing a
     * corpse is the whole failure mode for [Kind.CONNECTION].
     */
    fun evictsPool(kind: Kind): Boolean = kind == Kind.CONNECTION
}
