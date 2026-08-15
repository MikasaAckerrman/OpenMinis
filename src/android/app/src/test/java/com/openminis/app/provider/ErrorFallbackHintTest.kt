package com.openminis.app.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [429/content-filter fallback CTA] Pins which terminal errors surface the
 * "Add backup model" shortcut on the inline error banner. See
 * [ErrorFallbackHint].
 */
class ErrorFallbackHintTest {

    @Test
    fun `content filter rejection suggests a fallback`() {
        val cf = "Provider error: the gateway's content filter rejected this request " +
            "before it reached the model (sensitive words detected (request id: 2026...)) — " +
            "the text itself is fine for the model; another provider usually accepts it"
        assertTrue(ErrorFallbackHint.suggestsAddingFallback(cf))
    }

    @Test
    fun `rate limit variants suggest a fallback`() {
        assertTrue(ErrorFallbackHint.suggestsAddingFallback("Rate limited — please try again later"))
        assertTrue(ErrorFallbackHint.suggestsAddingFallback("HTTP 429: Too Many Requests"))
    }

    @Test
    fun `case is ignored`() {
        assertTrue(ErrorFallbackHint.suggestsAddingFallback("CONTENT FILTER triggered"))
    }

    @Test
    fun `unrelated errors do not suggest a fallback`() {
        // A different provider won't fix a bad key, a dead network, or an empty
        // wallet — these must NOT show the shortcut.
        assertFalse(ErrorFallbackHint.suggestsAddingFallback("Invalid API key"))
        assertFalse(ErrorFallbackHint.suggestsAddingFallback("Network error: timeout"))
        assertFalse(ErrorFallbackHint.suggestsAddingFallback("Quota exceeded: insufficient balance"))
    }

    @Test
    fun `null and blank are safe`() {
        assertFalse(ErrorFallbackHint.suggestsAddingFallback(null))
        assertFalse(ErrorFallbackHint.suggestsAddingFallback(""))
    }
}
