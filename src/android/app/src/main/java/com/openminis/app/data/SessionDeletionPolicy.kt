package com.openminis.app.data

/**
 * Hard product boundary for DESTROYING session data.
 *
 * Sibling of [CompactionLaunchPolicy], which guards context *rewriting*. This
 * one guards the irreversible half: dropping session rows and message rows off
 * disk. The two together state the product rule in code —
 *
 *   the agent may COMPRESS what it sends, and may never DELETE what is stored.
 *
 * ## Why this exists
 *
 * The clone/debug install ships `DebugServer` + the `minis-debug` CLI, so an
 * agent running in the PRoot shell can reach every registered RPC method. That
 * surface included `chat.session.delete`, i.e. one shell command was enough to
 * permanently destroy a conversation and all of its messages. `confirm=true`
 * was no protection at all: the agent writes its own params.
 *
 * Deleting a session is not recoverable. There is no undo, no tombstone, and no
 * export first. Compaction — the legitimate way to shrink a session — never
 * needs it: a compact marker leaves every message row on disk and only changes
 * what gets SENT. So no agent-side workflow has a reason to delete, and the
 * capability was pure downside.
 *
 * ## The rule
 *
 * Deletion requires a human acting in the UI ([Origin.USER_UI]). Everything
 * that reaches the app over RPC is [Origin.AGENT_RPC] and is refused, with one
 * narrow carve-out ([Origin.WORKER_CLEANUP]) described below.
 *
 * This is deliberately a *policy object* rather than an `if` at the call site:
 * a single place to read, a single place to test, and a compile-time-visible
 * name to grep for when someone adds the next deletion path.
 */
object SessionDeletionPolicy {

    enum class Origin {
        /**
         * A person tapped Delete in the session list / chat UI. The only origin
         * allowed to destroy a user-visible conversation.
         */
        USER_UI,

        /**
         * Anything arriving over the debug JSON-RPC surface — which is what
         * `minis-debug`, and therefore the agent in the sandbox, can call.
         * Never allowed to delete.
         */
        AGENT_RPC,

        /**
         * Teardown of an ephemeral sub-agent worker session that the app itself
         * created for a single delegated task (AgentSessionManager). These rows
         * are internal scaffolding, never a conversation the user typed into,
         * and leaking them would grow the DB without bound.
         *
         * Allowed — but see [mayDelete]: the caller must also prove the target
         * really is a worker session, so this cannot be used as a loophole to
         * delete an ordinary chat.
         */
        WORKER_CLEANUP,
    }

    /**
     * Whether [origin] may delete a session row (and its messages).
     *
     * [isEphemeralWorkerSession] is only consulted for [Origin.WORKER_CLEANUP]
     * and must be computed from stored session metadata (its `source`), not
     * from anything the caller passed in. Defaults to false so a caller that
     * forgets to supply it gets the safe answer.
     */
    fun mayDelete(
        origin: Origin,
        isEphemeralWorkerSession: Boolean = false,
    ): Boolean = when (origin) {
        Origin.USER_UI -> true
        Origin.WORKER_CLEANUP -> isEphemeralWorkerSession
        Origin.AGENT_RPC -> false
    }

    /**
     * Whether [origin] may delete individual MESSAGE rows — the `retry` /
     * `re-run from here` family, which drops the messages after a chosen point
     * before regenerating.
     *
     * Same boundary, and for the same reason: those rows are gone for good. A
     * human re-running a turn in the UI accepts that; an agent deciding on its
     * own to discard part of the transcript does not get to.
     *
     * Note this is what makes the rule complete. Blocking only whole-session
     * deletion while leaving message deletion open would let the same data loss
     * happen one turn at a time.
     */
    fun mayDeleteMessages(origin: Origin): Boolean = origin == Origin.USER_UI

    /**
     * Human-readable refusal, surfaced verbatim as the RPC error message so the
     * agent (and whoever reads the log) learns the boundary instead of retrying
     * with different params.
     */
    const val REFUSAL_MESSAGE: String =
        "Deleting sessions or messages is reserved for the user in the app UI. " +
            "The agent may compact a session (chat.compact.before / " +
            "chat.session.rescue), which keeps every message on disk and only " +
            "shrinks what is sent to the model."
}
