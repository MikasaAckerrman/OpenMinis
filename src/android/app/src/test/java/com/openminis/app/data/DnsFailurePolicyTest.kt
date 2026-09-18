package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DnsFailurePolicy].
 *
 * The contract worth protecting: recognise a name-resolution failure (so the
 * retry path can wait for connectivity instead of burning its ladder), and do
 * NOT claim resolve-failure for other transport errors, which must keep their
 * fast retry.
 */
class DnsFailurePolicyTest {

    // ---- positives: real resolver failure texts ----

    @Test
    fun `recognises android resolver message`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure(
                "Unable to resolve host \"gorouter.app\": No address associated with hostname",
            ),
        )
    }

    @Test
    fun `recognises bare unable-to-resolve-host`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure("Unable to resolve host \"api.openai.com\""),
        )
    }

    @Test
    fun `recognises no-address-associated alone`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure("No address associated with hostname"),
        )
    }

    @Test
    fun `recognises musl getaddrinfo wording`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure(
                "nodename nor servname provided, or not known",
            ),
        )
    }

    @Test
    fun `recognises glibc wording`() {
        assertTrue(DnsFailurePolicy.isNameResolutionFailure("Name or service not known"))
    }

    @Test
    fun `recognises exception class name`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure(
                "java.net.UnknownHostException: gorouter.app",
            ),
        )
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(
            DnsFailurePolicy.isNameResolutionFailure(
                "UNABLE TO RESOLVE HOST \"GOROUTER.APP\"",
            ),
        )
    }

    // ---- negatives: other transport errors must keep the fast ladder ----

    @Test
    fun `ttfb watchdog is not a resolve failure`() {
        assertFalse(
            DnsFailurePolicy.isNameResolutionFailure(
                "no response from server (120s) — check network/proxy",
            ),
        )
    }

    @Test
    fun `stream reset is not a resolve failure`() {
        assertFalse(
            DnsFailurePolicy.isNameResolutionFailure("stream was reset: INTERNAL_ERROR"),
        )
    }

    @Test
    fun `connection closed is not a resolve failure`() {
        assertFalse(DnsFailurePolicy.isNameResolutionFailure("connection closed"))
    }

    @Test
    fun `connection abort is not a resolve failure`() {
        assertFalse(
            DnsFailurePolicy.isNameResolutionFailure("Software caused connection abort"),
        )
    }

    @Test
    fun `socket timeout is not a resolve failure`() {
        assertFalse(DnsFailurePolicy.isNameResolutionFailure("timeout"))
    }

    @Test
    fun `provider 5xx is not a resolve failure`() {
        assertFalse(
            DnsFailurePolicy.isNameResolutionFailure("[503] upstream unavailable"),
        )
    }

    @Test
    fun `context length error is not a resolve failure`() {
        assertFalse(
            DnsFailurePolicy.isNameResolutionFailure("This model's maximum context length is 128000 tokens"),
        )
    }

    // ---- edge cases ----

    @Test
    fun `null message is not a resolve failure`() {
        assertFalse(DnsFailurePolicy.isNameResolutionFailure(null))
    }

    @Test
    fun `blank message is not a resolve failure`() {
        assertFalse(DnsFailurePolicy.isNameResolutionFailure("   "))
    }
}
