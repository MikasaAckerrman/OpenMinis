package com.openminis.app.service

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [B18 SessionMutex] The exclusive per-session turn lock: a second turn on
 * the SAME session must be refused (enqueue, never parallel), different
 * sessions are independent, and the lock is reusable after unlock.
 */
class SessionMutexTest {

    @Before
    fun reset() = SessionConcurrencyManager.resetForTest()

    @Test
    fun `second lock on the same session is refused`() {
        assertTrue(SessionConcurrencyManager.tryLockSession("s1"))
        assertFalse(
            "a second turn in the same chat must not run in parallel",
            SessionConcurrencyManager.tryLockSession("s1"),
        )
    }

    @Test
    fun `different sessions lock independently`() {
        assertTrue(SessionConcurrencyManager.tryLockSession("s1"))
        assertTrue(SessionConcurrencyManager.tryLockSession("s2"))
    }

    @Test
    fun `lock is reusable after unlock`() {
        assertTrue(SessionConcurrencyManager.tryLockSession("s1"))
        SessionConcurrencyManager.unlockSession("s1")
        assertTrue("the next turn of the same chat must acquire", SessionConcurrencyManager.tryLockSession("s1"))
    }

    @Test
    fun `setActive-style reset clears stale locks`() {
        assertTrue(SessionConcurrencyManager.tryLockSession("s1"))
        SessionConcurrencyManager.resetForTest()
        assertTrue("reset (process-level re-init) must clear locks", SessionConcurrencyManager.tryLockSession("s1"))
    }

    @Test
    fun `parallel turns on one session - lock serializes them`() = runTest {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondAcquired = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val owner = launch {
            assertTrue(SessionConcurrencyManager.tryLockSession("chat-1"))
            held.complete(Unit)
            // Hold while the second turn tries to acquire.
            secondAcquired.complete(SessionConcurrencyManager.tryLockSession("chat-1"))
        }
        held.await()
        val worker = launch {
            // The SECOND turn on the same session: must be refused, never
            // run in parallel with the holder.
            secondAcquired.await()
        }
        worker.join()
        owner.join()
        assertFalse(secondAcquired.await())
    }
}
