package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossInstanceFallbackTest {

    private fun route(
        instanceId: String,
        host: String,
        modelId: String = "model-x",
        entryId: String = "entry-${instanceId}",
    ) = CrossInstanceFallback.Route(
        instanceId = instanceId,
        entryId = entryId,
        modelId = modelId,
        host = host,
    )

    @Test
    fun `returns empty when there are no candidates`() {
        assertTrue(
            CrossInstanceFallback.select(
                primaryInstanceId = "primary",
                primaryHost = "broken.example",
                primaryModelId = "model-x",
                candidates = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `skips same-route duplicate that would just hit the same broken host`() {
        val result = CrossInstanceFallback.select(
            primaryInstanceId = "primary",
            primaryHost = "broken.example",
            primaryModelId = "model-x",
            candidates = listOf(
                route("alpha", "alpha.example"),
                route("sibling-on-broken", "broken.example"),
                route("beta", "beta.example"),
            ),
        )
        assertEquals(listOf("beta", "alpha"), result.map { it.instanceId })
    }

    @Test
    fun `respects limit on large candidate pool`() {
        val candidates = (1..20).map { route("inst-$it", "host-$it.example") }
        val result = CrossInstanceFallback.select(
            primaryInstanceId = "primary",
            primaryHost = "broken.example",
            primaryModelId = "model-x",
            candidates = candidates,
            limit = 5,
        )
        assertEquals(5, result.size)
    }

    @Test
    fun `falls back to other instances when primary host is unknown`() {
        val result = CrossInstanceFallback.select(
            primaryInstanceId = "primary",
            primaryHost = "outside-relay.example",
            primaryModelId = "model-x",
            candidates = listOf(
                route("alpha", "alpha.example"),
                route("beta", "beta.example"),
            ),
        )
        assertEquals(listOf("alpha", "beta"), result.map { it.instanceId })
    }

    @Test
    fun `different-model entries on other hosts are kept`() {
        val result = CrossInstanceFallback.select(
            primaryInstanceId = "primary",
            primaryHost = "broken.example",
            primaryModelId = "model-x",
            candidates = listOf(
                route("alpha", "alpha.example", modelId = "model-y"),
                route("beta", "beta.example", modelId = "model-z"),
            ),
        )
        assertEquals(listOf("alpha", "beta"), result.map { it.instanceId })
    }

    @Test
    fun `caller's primary instance is never returned even if leaked into list`() {
        val result = CrossInstanceFallback.select(
            primaryInstanceId = "primary",
            primaryHost = "broken.example",
            primaryModelId = "model-x",
            candidates = listOf(
                route("primary", "broken.example"),
                route("alt", "alt.example"),
            ),
        )
        assertEquals(listOf("alt"), result.map { it.instanceId })
    }
}
