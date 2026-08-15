package com.openminis.app.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [429-concurrent-sessions] App-wide admission control across sessions:
 * per-host rate pacing + a global cap on simultaneous streams. Uses an
 * injected clock and pinned jitter so timing is deterministic. See
 * [LlmDispatchGate].
 */
class LlmDispatchGateTest {

    @After
    fun tearDown() {
        LlmDispatchGate.resetForTest()
        LlmDispatchGate.clock = { System.currentTimeMillis() }
        LlmDispatchGate.jitterFraction = { 0.0 }
    }

    @Test
    fun `keyForUrl extracts host and falls back on non-url`() {
        assertEquals("api.openai.com", LlmDispatchGate.keyForUrl("https://api.openai.com/v1"))
        assertEquals("not a url", LlmDispatchGate.keyForUrl("not a url"))
        assertTrue(
            LlmDispatchGate.keyForUrl("https://a.com/x") != LlmDispatchGate.keyForUrl("https://b.com/x")
        )
    }

    @Test
    fun `rate slot admits burst then blocks until tokens accrue`() = runBlocking {
        LlmDispatchGate.resetForTest()
        LlmDispatchGate.jitterFraction = { 0.0 }
        var t = 0L
        LlmDispatchGate.clock = { t }
        LlmDispatchGate.burstCapacity = 3.0
        LlmDispatchGate.defaultRpm = 60.0 // 1 token/sec

        repeat(3) { withTimeout(500) { LlmDispatchGate.awaitRateSlot("k1") } }

        val job = launch { LlmDispatchGate.awaitRateSlot("k1") }
        delay(50)
        assertTrue("4th acquire must block on empty bucket", job.isActive)
        t = 2000L
        withTimeout(2000) { job.join() }
        assertTrue("4th acquire admitted after clock advance", !job.isActive)
    }

    @Test
    fun `buckets are independent per key`() = runBlocking {
        LlmDispatchGate.resetForTest()
        LlmDispatchGate.jitterFraction = { 0.0 }
        var t = 0L
        LlmDispatchGate.clock = { t }
        LlmDispatchGate.burstCapacity = 1.0
        LlmDispatchGate.defaultRpm = 6.0

        withTimeout(500) { LlmDispatchGate.awaitRateSlot("hostA") }
        // hostA drained, hostB has its own full bucket → must not block
        withTimeout(500) { LlmDispatchGate.awaitRateSlot("hostB") }
    }

    @Test
    fun `stream permit caps concurrency`() = runBlocking {
        LlmDispatchGate.resetForTest()
        LlmDispatchGate.maxConcurrentStreams = 2
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (1..6).map {
            launch(Dispatchers.Default) {
                LlmDispatchGate.withStreamPermit {
                    val cur = active.incrementAndGet()
                    peak.updateAndGet { p -> maxOf(p, cur) }
                    delay(100)
                    active.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertTrue("peak concurrency ${peak.get()} must be <= 2", peak.get() <= 2)
    }

    @Test
    fun `permit is released on cancellation`() = runBlocking {
        LlmDispatchGate.resetForTest()
        LlmDispatchGate.maxConcurrentStreams = 1
        val hog = launch { LlmDispatchGate.withStreamPermit { delay(10_000) } }
        delay(50)
        assertTrue(hog.isActive)
        hog.cancelAndJoin()
        val ok = withTimeoutOrNull(500) { LlmDispatchGate.withStreamPermit { 42 } }
        assertEquals(42, ok)
    }
}
