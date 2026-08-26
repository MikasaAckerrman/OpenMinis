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
}
