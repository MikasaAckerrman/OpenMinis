package com.openminis.app.engine

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [B42-network-retry] The pure round-retry policy: only the transport
 * class retries, never after a tool was dispatched, three attempts on the
 * 1.5/3/6s ladder.
 */
class NetworkRetryPolicyTest {

    // ── classification: type-based ────────────────────────────────────

    @Test
    fun `transport exceptions classify as transient`() {
        assertTrue(NetworkRetryPolicy.isTransientCause(SocketTimeoutException("read timed out")))
        assertTrue(NetworkRetryPolicy.isTransientCause(UnknownHostException("api.example.com")))
        assertTrue(NetworkRetryPolicy.isTransientCause(ConnectException("refused")))
        assertTrue(NetworkRetryPolicy.isTransientCause(java.io.IOException("connection closed")))
    }

    @Test
    fun `provider-wrapped transport errors classify as transient`() {
        assertTrue(NetworkRetryPolicy.isTransientCause(LLMError.NetworkError(IOException("x"))))
        assertTrue(NetworkRetryPolicy.isTransientCause(LLMError.TransientError("upstream hiccup")))
        assertTrue(NetworkRetryPolicy.isTransientCause(LLMError.RateLimited()))
    }

    @Test
    fun `deterministic failures never classify as transient`() {
        assertFalse(NetworkRetryPolicy.isTransientCause(LLMError.InvalidApiKey()))
        assertFalse(
            NetworkRetryPolicy.isTransientCause(
                LLMError.ProviderError("content filter rejected the request"),
            ),
        )
        assertFalse(NetworkRetryPolicy.isTransientCause(IllegalStateException("bad state")))
        assertFalse(NetworkRetryPolicy.isTransientCause(null))
    }

    // ── classification: message fallback ──────────────────────────────

    @Test
    fun `observed transport phrases classify as transient`() {
        // The vc154 live incident failed with exactly this phrase.
        assertTrue(NetworkRetryPolicy.isTransientMessage("request failed: stream idle timeout"))
        assertTrue(NetworkRetryPolicy.isTransientMessage("Unable to resolve host api.x.ai"))
        assertTrue(NetworkRetryPolicy.isTransientMessage("Connection reset by peer"))
    }

    @Test
    fun `http status shapes match the legacy anchored forms`() {
        assertTrue(NetworkRetryPolicy.isTransientMessage("HTTP 502: bad gateway"))
        assertTrue(NetworkRetryPolicy.isTransientMessage("[503] service unavailable"))
        assertTrue(NetworkRetryPolicy.isTransientMessage("[429] rate limited"))
    }

    @Test
    fun `status digits inside identifiers do not match`() {
        // The bare-digit regex once retried content-filter rejections
        // because a request-id contained "0512" — the anchored shapes must
        // keep that class deterministic.
        assertFalse(NetworkRetryPolicy.isTransientMessage("req_0512778 rejected"))
        assertFalse(NetworkRetryPolicy.isTransientMessage("HTTP 401: unauthorized"))
        assertFalse(NetworkRetryPolicy.isTransientMessage("HTTP 400: invalid reasoning_effort"))
        assertFalse(NetworkRetryPolicy.isTransientMessage(""))
        assertFalse(NetworkRetryPolicy.isTransientMessage(null))
    }

    // ── the gate ──────────────────────────────────────────────────────

    @Test
    fun `retry gate requires transient no-dispatch and budget`() {
        assertTrue(NetworkRetryPolicy.shouldRetry(transient = true, attempt = 0, toolsDispatched = false))
        assertTrue(NetworkRetryPolicy.shouldRetry(transient = true, attempt = 2, toolsDispatched = false))
        // A dispatched tool re-running on the retried request is the
        // double-execution invariant — never retry past it.
        assertFalse(NetworkRetryPolicy.shouldRetry(transient = true, attempt = 0, toolsDispatched = true))
        // Deterministic classes surface to the user immediately.
        assertFalse(NetworkRetryPolicy.shouldRetry(transient = false, attempt = 0, toolsDispatched = false))
        // Three retries spent — the failure surfaces with the Resume path.
        assertFalse(NetworkRetryPolicy.shouldRetry(transient = true, attempt = 3, toolsDispatched = false))
    }

    // ── the ladder ────────────────────────────────────────────────────

    @Test
    fun `backoff climbs 1_5s 3s 6s`() {
        assertEquals(1_500L, NetworkRetryPolicy.waitMsForAttempt(1))
        assertEquals(3_000L, NetworkRetryPolicy.waitMsForAttempt(2))
        assertEquals(6_000L, NetworkRetryPolicy.waitMsForAttempt(3))
        // Direct callers beyond the gate see the last rung, never a crash.
        assertEquals(6_000L, NetworkRetryPolicy.waitMsForAttempt(4))
        assertEquals(1_500L, NetworkRetryPolicy.waitMsForAttempt(0))
    }
}
