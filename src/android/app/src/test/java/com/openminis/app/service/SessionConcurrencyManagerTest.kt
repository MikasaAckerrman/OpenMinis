package com.openminis.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionConcurrencyManagerTest {

    @Before
    fun setUp() = SessionConcurrencyManager.resetForTest()

    @After
    fun tearDown() = SessionConcurrencyManager.resetForTest()

    @Test
    fun `ten independent sessions run and eleventh waits FIFO`() = runBlocking {
        val releases = (1..10).associateWith { CompletableDeferred<Unit>() }
        val entered = mutableListOf<Int>()
        val jobs = (1..10).map { id ->
            launch {
                SessionConcurrencyManager.withSlot("s$id") {
                    synchronized(entered) { entered += id }
                    releases.getValue(id).await()
                }
            }
        }

        withTimeout(2_000) {
            while (synchronized(entered) { entered.size } < 10) delay(5)
        }
        assertEquals(10, SessionConcurrencyManager.runningSessions.value.size)

        val eleventhEntered = CompletableDeferred<Unit>()
        val eleventh = launch {
            SessionConcurrencyManager.withSlot("s11") { eleventhEntered.complete(Unit) }
        }
        delay(50)
        assertFalse("11th session must wait", eleventhEntered.isCompleted)
        assertEquals(listOf("s11"), SessionConcurrencyManager.suspendedSessions.value)

        releases.getValue(1).complete(Unit)
        withTimeout(2_000) { eleventhEntered.await() }
        assertTrue(eleventhEntered.isCompleted)

        releases.values.forEach { it.complete(Unit) }
        jobs.forEach { it.join() }
        eleventh.join()
        assertTrue(SessionConcurrencyManager.runningSessions.value.isEmpty())
        assertTrue(SessionConcurrencyManager.suspendedSessions.value.isEmpty())
    }

    @Test
    fun `cancelled waiter is removed and never consumes a slot`() = runBlocking {
        val releases = (1..10).associateWith { CompletableDeferred<Unit>() }
        val holders = (1..10).map { id ->
            launch { SessionConcurrencyManager.withSlot("h$id") { releases.getValue(id).await() } }
        }
        withTimeout(2_000) {
            while (SessionConcurrencyManager.runningSessions.value.size < 10) delay(5)
        }

        val cancelled = launch { SessionConcurrencyManager.acquireSlot("cancelled") }
        withTimeout(2_000) {
            while ("cancelled" !in SessionConcurrencyManager.suspendedSessions.value) delay(5)
        }
        cancelled.cancelAndJoin()
        assertFalse("cancelled" in SessionConcurrencyManager.suspendedSessions.value)

        releases.getValue(1).complete(Unit)
        val admitted = withTimeoutOrNull(2_000) {
            SessionConcurrencyManager.withSlot("replacement") { true }
        }
        assertEquals(true, admitted)
        assertFalse("cancelled" in SessionConcurrencyManager.runningSessions.value)

        releases.values.forEach { it.complete(Unit) }
        holders.forEach { it.join() }
    }
}
