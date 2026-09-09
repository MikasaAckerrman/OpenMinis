package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackIdentityTest {

    @Test
    fun `same model through another endpoint is a real fallback`() {
        assertTrue(
            ProviderFallbackIdentity.isUsableFallback(
                currentModelId = "gpt-test",
                currentThrottleKey = "relay-a.example",
                nextModelId = "gpt-test",
                nextThrottleKey = "relay-b.example",
            ),
        )
    }

    @Test
    fun `same model and same endpoint is duplicate route`() {
        assertFalse(
            ProviderFallbackIdentity.isUsableFallback(
                currentModelId = "gpt-test",
                currentThrottleKey = "relay-a.example",
                nextModelId = "gpt-test",
                nextThrottleKey = "RELAY-A.EXAMPLE",
            ),
        )
    }

    @Test
    fun `different model is usable even on same endpoint`() {
        assertTrue(
            ProviderFallbackIdentity.isUsableFallback(
                currentModelId = "model-a",
                currentThrottleKey = "relay.example",
                nextModelId = "model-b",
                nextThrottleKey = "relay.example",
            ),
        )
    }

    @Test
    fun `candidate order starts after exact route and keeps same-model next endpoint`() {
        val routes = listOf(
            ProviderFallbackIdentity.Route("gpt-test", "relay-a.example"),
            ProviderFallbackIdentity.Route("gpt-test", "relay-b.example"),
            ProviderFallbackIdentity.Route("model-c", "relay-c.example"),
        )

        assertTrue(
            ProviderFallbackIdentity.orderedCandidateIndices(
                routes = routes,
                currentModelId = "gpt-test",
                currentThrottleKey = "relay-a.example",
            ) == listOf(1, 2),
        )
    }

    @Test
    fun `candidate order skips duplicate same route`() {
        val routes = listOf(
            ProviderFallbackIdentity.Route("gpt-test", "relay-a.example"),
            ProviderFallbackIdentity.Route("gpt-test", "RELAY-A.EXAMPLE"),
            ProviderFallbackIdentity.Route("gpt-test", "relay-b.example"),
        )

        assertTrue(
            ProviderFallbackIdentity.orderedCandidateIndices(
                routes = routes,
                currentModelId = "gpt-test",
                currentThrottleKey = "relay-a.example",
            ) == listOf(2),
        )
    }
}
