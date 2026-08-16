package com.openminis.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionActivityTrackerConcurrencyTest {
    private val pool = Executors.newFixedThreadPool(32)

    @After
    fun tearDown() {
        SessionActivityTracker.resetForTest()
        pool.shutdownNow()
    }

    @Test
    fun `parallel session starts and finishes never lose another active id`() {
        repeat(200) { round ->
            SessionActivityTracker.resetForTest()
            val ids = (0 until 32).map { "r${round}_s$it" }
            runTogether(ids) { SessionActivityTracker.setActive(it) }
            assertEquals("lost active session in round $round", ids.toSet(), SessionActivityTracker.activeSessions.value)

            val keep = ids.last()
            runTogether(ids.dropLast(1)) { SessionActivityTracker.setInactive(it) }
            assertEquals("finishing peers removed live session in round $round", setOf(keep), SessionActivityTracker.activeSessions.value)
        }
    }

    @Test
    fun `parallel presence updates retain every chat`() {
        repeat(200) { round ->
            SessionActivityTracker.resetForTest()
            val ids = (0 until 32).map { "p${round}_s$it" }
            runTogether(ids) { SessionActivityTracker.setPresent(it) }
            assertEquals("lost present session in round $round", ids.toSet(), SessionActivityTracker.presentSessions.value)
        }
    }

    private fun runTogether(ids: List<String>, action: (String) -> Unit) {
        val ready = CountDownLatch(ids.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(ids.size)
        ids.forEach { id ->
            pool.execute {
                ready.countDown()
                start.await()
                try {
                    action(id)
                } finally {
                    done.countDown()
                }
            }
        }
        check(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        check(done.await(10, TimeUnit.SECONDS))
    }
}
