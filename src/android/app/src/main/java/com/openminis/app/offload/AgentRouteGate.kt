package com.openminis.app.offload

/**
 * Decides HOW a turn should be routed before any money is spent: forced to the
 * agent team by the user, classified by the cheap router, or answered directly.
 *
 * Why this is its own class with no Android imports: the send path in
 * ChatViewModel is ~1200 lines of coroutine and provider plumbing that cannot
 * run in the sandbox, so every past routing bug was found by installing an APK.
 * The three inputs below are the whole decision, they are pure data, and they
 * are unit-tested — a wrong answer here is now caught by kotlinc, not by a
 * device probe.
 *
 * The composer's "Agents" button maps to [forcedByUser]. It deliberately does
 * NOT go through the classifier: the user already decided that this request is
 * worth the team, and paying for a classifier call to be told what we were told
 * is pure waste. It also means the button works on an install that never
 * configured `agent.autoRouteModel`, which is the common case.
 */
object AgentRouteGate {

    enum class Intent {
        /**
         * Answer in this chat. Either nothing asked for the team, or this
         * session IS a team member (see [Decision.reason]).
         */
        NORMAL_CHAT,

        /** Run the team without classifying — the user pressed the button. */
        FORCE_GRAPH,

        /** Ask the cheap router whether this turn deserves the team. */
        CLASSIFY,
    }

    data class Decision(val intent: Intent, val reason: String)

    /**
     * @param forcedByUser composer "Agents" toggle for THIS session.
     * @param autoRouteEnabled `agent.autoRoute` — the classifier's master switch.
     * @param isGraphWorker true when this session is a node of a running graph.
     */
    fun decide(
        forcedByUser: Boolean,
        autoRouteEnabled: Boolean,
        isGraphWorker: Boolean,
    ): Decision {
        // A worker never routes, and this check comes FIRST — before the forced
        // flag — because it is the recursion barrier. A worker whose session
        // somehow carries a forced flag would spawn a graph, whose workers would
        // spawn graphs, each level paying for the whole subtree. Per-session
        // storage should already keep the flag off a worker; this is the second
        // lock on the same door, and the cheap one.
        if (isGraphWorker) {
            return Decision(Intent.NORMAL_CHAT, "graph worker — never routes")
        }
        if (forcedByUser) {
            return Decision(Intent.FORCE_GRAPH, "user forced the agent team")
        }
        if (autoRouteEnabled) {
            return Decision(Intent.CLASSIFY, "auto-routing on — asking the classifier")
        }
        return Decision(Intent.NORMAL_CHAT, "auto-routing off and not forced")
    }

    /**
     * Graph to run when the user forced the team and no classifier level exists.
     *
     * A pinned `agent.defaultGraph` wins — the user chose it explicitly. With
     * nothing pinned this returns the LIGHT graph, not the full one: the button
     * means "use the team", and the cheapest arrangement that is still a team
     * beats seven nodes the user did not ask to pay for. Escalating is a
     * deliberate settings choice, and it should stay deliberate.
     *
     * @param configuredGraphId `agent.defaultGraph`, blank when unset.
     * @param graphExists resolves a graph id against storage; a pinned id can be
     *   stale after the graph was deleted, and falling back beats failing the turn.
     */
    fun forcedGraphId(
        configuredGraphId: String?,
        graphExists: (String) -> Boolean,
    ): String {
        val pinned = configuredGraphId?.trim().orEmpty()
        if (pinned.isNotEmpty() && graphExists(pinned)) return pinned
        return BuiltinGraphs.ID_LIGHT
    }
}
