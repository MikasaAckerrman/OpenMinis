package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-no-agent-session-deletion] The boundary is a one-line policy, so these
 * tests are short by design — their job is to make the rule impossible to
 * loosen by accident. If someone widens [SessionDeletionPolicy.mayDelete],
 * these fail and say why in the message.
 */
class SessionDeletionPolicyTest {

    @Test
    fun `agent rpc can never delete a session`() {
        assertFalse(
            "RPC is what minis-debug (i.e. the agent) can reach — it must never delete",
            SessionDeletionPolicy.mayDelete(SessionDeletionPolicy.Origin.AGENT_RPC),
        )
        // And it stays refused even if a caller claims the target is a worker.
        assertFalse(
            "the worker carve-out must not be reachable from AGENT_RPC",
            SessionDeletionPolicy.mayDelete(
                SessionDeletionPolicy.Origin.AGENT_RPC,
                isEphemeralWorkerSession = true,
            ),
        )
    }

    @Test
    fun `agent rpc can never delete messages either`() {
        // Blocking whole-session deletion while leaving message deletion open
        // would let the same loss happen one turn at a time.
        assertFalse(
            SessionDeletionPolicy.mayDeleteMessages(SessionDeletionPolicy.Origin.AGENT_RPC),
        )
        assertFalse(
            SessionDeletionPolicy.mayDeleteMessages(SessionDeletionPolicy.Origin.WORKER_CLEANUP),
        )
        assertTrue(
            SessionDeletionPolicy.mayDeleteMessages(SessionDeletionPolicy.Origin.USER_UI),
        )
    }

    @Test
    fun `user in the ui may delete`() {
        assertTrue(SessionDeletionPolicy.mayDelete(SessionDeletionPolicy.Origin.USER_UI))
    }

    @Test
    fun `worker cleanup only applies to proven worker sessions`() {
        assertTrue(
            "ephemeral worker scaffolding may be torn down",
            SessionDeletionPolicy.mayDelete(
                SessionDeletionPolicy.Origin.WORKER_CLEANUP,
                isEphemeralWorkerSession = true,
            ),
        )
        assertFalse(
            "an ordinary chat must not be deletable through the worker path",
            SessionDeletionPolicy.mayDelete(
                SessionDeletionPolicy.Origin.WORKER_CLEANUP,
                isEphemeralWorkerSession = false,
            ),
        )
    }

    @Test
    fun `worker flag defaults to the safe answer`() {
        // A caller that forgets to pass the flag must not get a deletion.
        assertFalse(
            SessionDeletionPolicy.mayDelete(SessionDeletionPolicy.Origin.WORKER_CLEANUP),
        )
    }

    @Test
    fun `only one origin can delete sessions`() {
        val allowed = SessionDeletionPolicy.Origin.entries.filter {
            SessionDeletionPolicy.mayDelete(it)
        }
        assertEquals(
            "exactly one origin may delete with default flags: USER_UI",
            listOf(SessionDeletionPolicy.Origin.USER_UI),
            allowed,
        )
    }

    @Test
    fun `refusal message points at the non-destructive alternative`() {
        val m = SessionDeletionPolicy.REFUSAL_MESSAGE
        assertTrue("names the UI as the only route", m.contains("app UI"))
        assertTrue("offers compaction instead", m.contains("compact"))
        assertTrue(
            "states that compaction keeps data on disk",
            m.contains("on disk"),
        )
    }
}
