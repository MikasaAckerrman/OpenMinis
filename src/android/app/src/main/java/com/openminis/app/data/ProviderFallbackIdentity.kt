package com.openminis.app.data

/**
 * Defines provider-route identity for fallback chains.
 *
 * A model id alone is not a route: the same model can be served by multiple
 * provider instances / relay hosts, and a content filter is an endpoint
 * property. Treating same-model entries as identical makes a valid clean
 * endpoint unreachable after a relay-specific `sensitive_words` rejection.
 */
object ProviderFallbackIdentity {
    data class Route(val modelId: String, val throttleKey: String)

    fun isSameRoute(
        currentModelId: String,
        currentThrottleKey: String,
        nextModelId: String,
        nextThrottleKey: String,
    ): Boolean =
        currentModelId == nextModelId &&
            currentThrottleKey.equals(nextThrottleKey, ignoreCase = true)

    fun isUsableFallback(
        currentModelId: String,
        currentThrottleKey: String,
        nextModelId: String,
        nextThrottleKey: String,
    ): Boolean = !isSameRoute(
        currentModelId = currentModelId,
        currentThrottleKey = currentThrottleKey,
        nextModelId = nextModelId,
        nextThrottleKey = nextThrottleKey,
    )

    /**
     * Group member indices in fallback order after the exact current route.
     * Duplicate model+endpoint routes are returned once; same-model entries on
     * another endpoint remain candidates because the relay is what filtered
     * the request, not the model.
     */
    fun orderedCandidateIndices(
        routes: List<Route>,
        currentModelId: String,
        currentThrottleKey: String,
    ): List<Int> {
        if (routes.size < 2) return emptyList()
        val currentIdx = routes.indexOfFirst {
            isSameRoute(
                currentModelId,
                currentThrottleKey,
                it.modelId,
                it.throttleKey,
            )
        }
        val seen = mutableSetOf(routeKey(currentModelId, currentThrottleKey))
        val out = ArrayList<Int>(routes.size - 1)
        for (offset in 1 until routes.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % routes.size else offset
            val route = routes[idx]
            if (seen.add(routeKey(route.modelId, route.throttleKey))) out.add(idx)
        }
        return out
    }

    private fun routeKey(modelId: String, throttleKey: String): String =
        "$modelId\u0000${throttleKey.lowercase()}"
}
