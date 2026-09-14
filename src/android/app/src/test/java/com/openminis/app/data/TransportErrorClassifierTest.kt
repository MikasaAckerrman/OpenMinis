package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two error detectors the AI-compaction split-retry path depends on
 * ([com.openminis.app.ui.chat.ChatViewModel.generateCompactSummaryWithSplitting]).
 * These were extracted from the removed RescueAdvisor; the behaviour must not
 * change, because a false negative on `isVagueTransportFailure` means `/compact`
 * gives up on an oversized session instead of splitting and retrying.
 */
class TransportErrorClassifierTest {

    @Test
    fun `explicit size errors are recognised`() {
        assertTrue(TransportErrorClassifier.isExplicitSizeError("context length exceeded"))
        assertTrue(TransportErrorClassifier.isExplicitSizeError("Request too large for this model"))
        assertTrue(TransportErrorClassifier.isExplicitSizeError("HTTP 413 Payload Too Large"))
        assertTrue(TransportErrorClassifier.isExplicitSizeError("prompt is too long"))
    }

    @Test
    fun `non-size errors are not flagged as explicit size`() {
        assertFalse(TransportErrorClassifier.isExplicitSizeError("401 unauthorized"))
        assertFalse(TransportErrorClassifier.isExplicitSizeError("model refused to answer"))
    }

    @Test
    fun `vague transport failures are recognised`() {
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("no response from server (30s)"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("Connection reset by peer"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("upstream returned 502 Bad Gateway"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("stream closed unexpectedly (EOF)"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("read timed out"))
    }

    @Test
    fun `a clean auth error is not a vague transport failure`() {
        assertFalse(TransportErrorClassifier.isVagueTransportFailure("401 invalid api key"))
        assertFalse(TransportErrorClassifier.isVagueTransportFailure("content policy violation"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(TransportErrorClassifier.isExplicitSizeError("CONTEXT WINDOW exceeded"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("TIMEOUT waiting for headers"))
    }

    /**
     * Regression: live failure 2026-09-14 with AgentRouter/minimax — the
     * agent saw "compaction failed: provider error: the gat" and never hit
     * the split-retry path because none of the size/vague markers matched.
     * The split-retry is the ONLY thing that can shrink an over-large
     * session, so a false negative here leaves the user stuck with a
     * session too big to send AND unable to compact itself.
     */
    @Test
    fun `live AgentRouter gateway error triggers split-retry`() {
        // Exact user-reported form (truncated in chat, full form below)
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("provider error: the gat"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("provider error: the gateway returned an error"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("provider error: the gateway timed out"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("provider error: gateway 502"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("provider error: bad gateway"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("upstream returned 503"))
        assertTrue(TransportErrorClassifier.isVagueTransportFailure("relay rejected request"))
    }

    /**
     * None of these gateway-shaped errors should be confused with auth /
     * quota (which DEFINITELY_NOT_SIZE_MARKERS catches and short-circuits).
     * A false positive there would burn 6 retry calls on a doomed key.
     */
    @Test
    fun `gateway-shaped errors are not auth or quota errors`() {
        assertFalse(TransportErrorClassifier.isDefinitelyNotSizeRelated("provider error: the gateway returned an error"))
        assertFalse(TransportErrorClassifier.isDefinitelyNotSizeRelated("upstream 502"))
        assertFalse(TransportErrorClassifier.isDefinitelyNotSizeRelated("relay timeout"))
    }
}
