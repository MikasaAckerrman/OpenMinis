package com.openminis.app.offload

/**
 * Decides HOW a turn should be routed before any money is spent: classified
 * by the cheap router or answered directly.
 *
 * Why this is its own class with no Android imports: the send path in
 * ChatViewModel is ~1200 lines of coroutine and provider plumbing that cannot
 * run in the sandbox, so every past routing bug was found by installing an APK.
 * The three inputs below are the whole decision, they are pure data, and they
 * are unit-tested — a wrong answer here is now caught by kotlinc, not by a
 * device probe.
 */
object AgentRouteGate {

    enum class Intent {
        /**
         * Answer in this chat. Either nothing asked for the team, or this
         * session IS a team member (see [Decision.reason]).
         */
        NORMAL_CHAT,

        /** Ask the cheap router whether this turn deserves the team. */
        CLASSIFY,
    }

    data class Decision(val intent: Intent, val reason: String)

    /**
     * @param autoRouteEnabled `agent.autoRoute` — the classifier's master switch.
     * @param isGraphWorker true when this session is a node of a running graph.
     */
    fun decide(
        autoRouteEnabled: Boolean,
        isGraphWorker: Boolean,
    ): Decision {
        // A worker never routes — this is the recursion barrier: a worker
        // spawning a graph whose workers spawn graphs would pay for the whole
        // subtree on every level.
        if (isGraphWorker) {
            return Decision(Intent.NORMAL_CHAT, "graph worker — never routes")
        }
        if (autoRouteEnabled) {
            return Decision(Intent.CLASSIFY, "auto-routing on — asking the classifier")
        }
        return Decision(Intent.NORMAL_CHAT, "auto-routing off")
    }

}
