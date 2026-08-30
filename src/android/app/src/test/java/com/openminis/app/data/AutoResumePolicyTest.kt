package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-auto-resume] The journal measured 48 FAIL / 21 OK / 3 GIVEUP: the in-request
 * ladder heals most faults, and the three that died were all NO_RESPONSE with the
 * budget spent. These tests pin which of those may be resumed automatically.
 */
class AutoResumePolicyTest {

    private fun decide(
        cause: AutoResumePolicy.Cause,
        attemptsUsed: Int = 0,
        cancelled: Boolean = false,
        newMessage: Boolean = false,
    ) = AutoResumePolicy.decide(cause, attemptsUsed, cancelled, newMessage)

    // ── classification ──────────────────────────────────────────────────────

    @Test
    fun `the exact watchdog message from the device is NO_RESPONSE`() {
        // Copied verbatim from the user's screen, not paraphrased.
        val msg = "Transient error: no response from server (120s) — check network/proxy"
        assertEquals(
            AutoResumePolicy.Cause.NO_RESPONSE,
            AutoResumePolicy.classify(msg, isTransient = true),
        )
    }

    @Test
    fun `the exact 502 message from the device is BAD_GATEWAY`() {
        val msg = "Transient error: HTTP 502: error code: 502"
        assertEquals(
            AutoResumePolicy.Cause.BAD_GATEWAY,
            AutoResumePolicy.classify(msg, isTransient = true),
        )
    }

    @Test
    fun `the exact DNS message from the device is OFFLINE`() {
        val msg = "Unable to resolve host \"gorouter.app\": No address associated with hostname"
        assertEquals(
            AutoResumePolicy.Cause.OFFLINE,
            AutoResumePolicy.classify(msg, isTransient = true),
        )
    }

    @Test
    fun `a fatal error is never reclassified as transport, whatever its text says`() {
        // The type is the authority. This is the trap that made `is5xx` never
        // fire: it checked ProviderError while mapHttpError produced
        // TransientError, so a real 502 tested false.
        assertEquals(
            AutoResumePolicy.Cause.OTHER,
            AutoResumePolicy.classify("HTTP 502: error code: 502", isTransient = false),
        )
    }

    @Test
    fun `a request id that merely contains digits is not a gateway error`() {
        assertEquals(
            AutoResumePolicy.Cause.OTHER,
            AutoResumePolicy.classify("Provider error: req_a502f3 failed", isTransient = true),
        )
    }

    @Test
    fun `the stale-socket marker wins over the connection vocabulary`() {
        // The TTFB message is the more specific fact; treating it as CONNECTION
        // would apply the wrong backoff.
        val msg = "no response from server (120s) — check network/proxy, connection closed"
        assertEquals(
            AutoResumePolicy.Cause.NO_RESPONSE,
            AutoResumePolicy.classify(msg, isTransient = true),
        )
    }

    // ── what must never auto-resume ─────────────────────────────────────────

    @Test
    fun `a user cancel is never overridden`() {
        val d = decide(AutoResumePolicy.Cause.NO_RESPONSE, cancelled = true)
        assertTrue(d is AutoResumePolicy.Decision.Stop)
    }

    @Test
    fun `a superseded turn is not resumed`() {
        // The human moved on; resuming would interleave two conversations.
        val d = decide(AutoResumePolicy.Cause.BAD_GATEWAY, newMessage = true)
        assertTrue(d is AutoResumePolicy.Decision.Stop)
    }

    @Test
    fun `a non-transport failure is not resumed`() {
        // Auth, quota, 400, content filter: a replay reaches the identical
        // failure and spends tokens doing it.
        val d = decide(AutoResumePolicy.Cause.OTHER)
        assertTrue(d is AutoResumePolicy.Decision.Stop)
    }

    @Test
    fun `cancel outranks everything else`() {
        val d = decide(
            AutoResumePolicy.Cause.NO_RESPONSE,
            attemptsUsed = 0,
            cancelled = true,
            newMessage = true,
        )
        assertEquals(
            "user cancelled the turn",
            (d as AutoResumePolicy.Decision.Stop).reason,
        )
    }

    // ── the recoverable shape ───────────────────────────────────────────────

    @Test
    fun `the observed GIVEUP shape gets resumed`() {
        val d = decide(AutoResumePolicy.Cause.NO_RESPONSE, attemptsUsed = 0)
        val r = d as AutoResumePolicy.Decision.Resume
        assertEquals(1, r.attempt)
        assertEquals(15, r.delaySec)
    }

    @Test
    fun `the backoff grows steeply because the far side needs time`() {
        // Not 1/2/4: that ladder suits a socket failing instantly. A gateway that
        // just burned 120s of watchdog will not be healthy one second later.
        assertEquals(15, AutoResumePolicy.delaySecForAttempt(0))
        assertEquals(45, AutoResumePolicy.delaySecForAttempt(1))
        assertEquals(120, AutoResumePolicy.delaySecForAttempt(2))
    }

    @Test
    fun `out-of-range attempt numbers are clamped, never thrown`() {
        // A policy that crashes on the error path turns a recoverable failure
        // into a lost turn.
        assertEquals(15, AutoResumePolicy.delaySecForAttempt(-5))
        assertEquals(120, AutoResumePolicy.delaySecForAttempt(99))
    }

    @Test
    fun `the budget is bounded so a real outage surfaces`() {
        assertEquals(3, AutoResumePolicy.MAX_ATTEMPTS)
        assertTrue(
            decide(AutoResumePolicy.Cause.NO_RESPONSE, attemptsUsed = 2)
                is AutoResumePolicy.Decision.Resume,
        )
        assertTrue(
            decide(AutoResumePolicy.Cause.NO_RESPONSE, attemptsUsed = 3)
                is AutoResumePolicy.Decision.Stop,
        )
    }

    @Test
    fun `an overspent counter still stops rather than wrapping around`() {
        // `>=`, not `==`: the ceiling can be re-read per failure, so an exact
        // comparison could be skipped entirely.
        assertTrue(
            decide(AutoResumePolicy.Cause.NO_RESPONSE, attemptsUsed = 99)
                is AutoResumePolicy.Decision.Stop,
        )
    }

    @Test
    fun `every transport cause is eligible`() {
        for (c in listOf(
            AutoResumePolicy.Cause.NO_RESPONSE,
            AutoResumePolicy.Cause.BAD_GATEWAY,
            AutoResumePolicy.Cause.CONNECTION,
            AutoResumePolicy.Cause.OFFLINE,
        )) {
            assertTrue("$c must be resumable", decide(c) is AutoResumePolicy.Decision.Resume)
        }
    }

    // ── connectivity gating ─────────────────────────────────────────────────

    @Test
    fun `only a DNS failure waits for connectivity`() {
        // Every non-OFFLINE failure in the journal carried net=on: the network is
        // up and the far side is at fault, so waiting for connectivity would add
        // a pointless delay.
        assertTrue(AutoResumePolicy.awaitsConnectivity(AutoResumePolicy.Cause.OFFLINE))
        assertFalse(AutoResumePolicy.awaitsConnectivity(AutoResumePolicy.Cause.NO_RESPONSE))
        assertFalse(AutoResumePolicy.awaitsConnectivity(AutoResumePolicy.Cause.BAD_GATEWAY))
        assertFalse(AutoResumePolicy.awaitsConnectivity(AutoResumePolicy.Cause.CONNECTION))
    }
}
