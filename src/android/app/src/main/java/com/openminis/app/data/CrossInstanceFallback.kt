package com.openminis.app.data

/**
 * Builds a fallback candidate list across PROVIDER INSTANCES (not just entries
 * inside one model group) when a relay-specific rejection like
 * `sensitive_words` blocks a single endpoint.
 *
 * The model's per-session binding points at one instance. When that instance
 * fails (content filter, gateway error, rate limit), the only escape is a
 * different instance — same model, different host, or any other host the
 * user has configured. `provider_model_groups` is a separate, optional layer;
 * most sessions have no group, and a group fallback that returns `[]` means
 * the broken relay gets retried forever.
 *
 * Algorithm: collect `(modelId, host)` for every entry on every OTHER enabled
 * instance, run them through `ProviderFallbackIdentity.orderedCandidates`,
 * and return at most `limit` values in stable order. Same-host duplicates are
 * collapsed by the identity helper; same-model entries on other hosts stay
 * distinct routes.
 */
object CrossInstanceFallback {

    /**
     * One candidate route: an (instance id, entry id, model id, host) tuple
     * the caller can use to build a provider and switch to it.
     */
    data class Route(
        val instanceId: String,
        val entryId: String,
        val modelId: String,
        val host: String,
    )

    /**
     * @param primaryInstanceId the instance the user is currently using.
     * @param primaryHost host derived from `effectiveBaseURL` of the primary
     *   instance (the same throttleKey ChatViewModel uses).
     * @param primaryModelId id of the model bound to the primary entry.
     * @param candidates other-instance entries to consider. The caller is
     *   responsible for filtering to enabled instances + non-hidden entries
     *   and for excluding the primary instance.
     * @param limit maximum number of routes returned (>= 1). Defaults to 5
     *   to bound the worst-case retry storm when many instances fail.
     */
    fun select(
        primaryInstanceId: String,
        primaryHost: String,
        primaryModelId: String,
        candidates: List<Route>,
        limit: Int = 5,
    ): List<Route> {
        require(limit >= 1) { "limit must be >= 1" }
        if (candidates.isEmpty()) return emptyList()
        val identityRoutes = candidates.map { c ->
            ProviderFallbackIdentity.Route(
                modelId = c.modelId,
                throttleKey = c.host,
            )
        }
        val ordered = ProviderFallbackIdentity.orderedCandidates(
            routes = identityRoutes,
            values = candidates,
            currentModelId = primaryModelId,
            currentThrottleKey = primaryHost,
        )
        // orderedCandidates already de-dupes by route; we still cap by limit
        // and skip any candidate that points at the same instance we are
        // already on (e.g. an entry the caller accidentally left in).
        val seenInstances = mutableSetOf(primaryInstanceId)
        val out = ArrayList<Route>(limit)
        for (candidate in ordered) {
            if (!seenInstances.add(candidate.value.instanceId)) continue
            out.add(candidate.value)
            if (out.size >= limit) break
        }
        return out
    }
}
