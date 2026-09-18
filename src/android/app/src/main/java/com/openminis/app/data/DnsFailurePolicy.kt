package com.openminis.app.data

/**
 * Recognises name-resolution (DNS) failures from an error message.
 *
 * Why this is its own decision instead of "just another transient error":
 * a DNS failure carries information the generic transport path throws away.
 * `Unable to resolve host "gorouter.app": No address associated with hostname`
 * does NOT mean the endpoint is down — on Android it overwhelmingly means the
 * device had no usable network at the moment of the lookup (Doze had parked
 * the radio, or the link was mid-handover). Retrying such a request on a fixed
 * 1s/2s/4s ladder burns all three attempts while the radio is still down and
 * then reports failure to the user, even though waiting a few extra seconds
 * for connectivity would have succeeded.
 *
 * So the correct response to a resolve failure is "wait for the network to
 * come back, THEN retry", not "retry immediately N times". This object is the
 * pure, Android-free predicate that lets the retry path tell the two apart.
 *
 * Kept free of Android imports so it is unit-testable on the JVM.
 */
object DnsFailurePolicy {
    /**
     * Substrings that identify a name-resolution failure across the resolver
     * implementations Android/OkHttp surface:
     * - `UnknownHostException` from Android's resolver: "Unable to resolve host
     *   \"host\": No address associated with hostname"
     * - musl/bionic getaddrinfo text: "nodename nor servname provided"
     * - OkHttp/JDK wording variants: "failed to resolve", "name or service not known"
     *
     * Matched case-insensitively against the whole message.
     */
    private val RESOLVE_FAILURE_MARKERS = listOf(
        "unable to resolve host",
        "no address associated with hostname",
        "nodename nor servname",
        "name or service not known",
        "failed to resolve",
        "unknownhostexception",
    )

    /**
     * True when [message] is a name-resolution failure — i.e. the request never
     * reached the network because the hostname could not be turned into an IP.
     */
    fun isNameResolutionFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val m = message.lowercase()
        return RESOLVE_FAILURE_MARKERS.any { m.contains(it) }
    }
}
