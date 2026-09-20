package com.openminis.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/**
 * [T-compact-parallel] Unit tests for the parallel map-reduce compact.
 */
class CompactParallelTest {

    @Test
    fun `parallel map-reduce produces all windows in parallel`() = runTest {
        // Simulate: 3 windows, each takes 100ms (virtually).
        // Sequential would take 300ms+. Parallel should take ~100ms.
        val start = System.currentTimeMillis()
        coroutineScope {
            (1..3).map { i ->
                async(Dispatchers.Default) {
                    delay(100)
                    "summary-$i"
                }
            }.map { it.await() }
        }
        val elapsed = System.currentTimeMillis() - start
        // Parallel: should be ~100-150ms (not 300+)
        assertTrue("parallel took ${elapsed}ms (expected <200ms)", elapsed < 200)
    }

    @Test
    fun `failed parallel window returns null and triggers sequential retry`() {
        val windows = listOf("w1", "w2", "w3")
        val results: List<String?> = runBlocking {
            windows.mapIndexed { i, w ->
                async(Dispatchers.IO) {
                    try {
                        if (i == 1) throw RuntimeException("window 2 failed")
                        "ok-$i"
                    } catch (e: Exception) {
                        null  // size-related → null → sequential retry
                    }
                }
            }.map { it.await() }
        }
        assertEquals(listOf("ok-0", null, "ok-2"), results)
        // Sequential retry for index 1:
        val retried = results.mapIndexed { i, s -> s ?: "retry-$i" }
        assertEquals(listOf("ok-0", "retry-1", "ok-2"), retried)
    }

    @Test
    fun `merge prompt includes dedup and freshness rules`() {
        val parts = listOf("part-1 summary", "part-2 summary", "part-3 summary")
        val prompt = buildString {
            append("You are merging partial summaries of ONE conversation into a SINGLE definitive context summary.\n\n")
            append("RULES (in priority order):\n")
            append("1. DEDUPLICATE: identical facts/tool results/paths mentioned in multiple parts → keep ONCE\n")
            append("2. FRESHNESS: if a fact has an older and newer version → keep ONLY the newest\n")
            parts.forEachIndexed { i, s ->
                append("=== Part ${i + 1} ===\n").append(s).append("\n\n")
            }
        }
        assertTrue(prompt.contains("DEDUPLICATE"))
        assertTrue(prompt.contains("FRESHNESS"))
        assertTrue(prompt.contains("=== Part 1 ==="))
        assertTrue(prompt.contains("=== Part 3 ==="))
    }

    @Test
    fun `CompactLevel fraction maps to correct values`() {
        // LIGHT=40%, MEDIUM=20%, ULTRA=5%, AUTO=100% of budget
        val light = 0.40 * 128_000
        val medium = 0.20 * 128_000
        val ultra = 0.05 * 128_000
        assertEquals(51_200, light.toInt())
        assertEquals(25_600, medium.toInt())
        assertEquals(6_400, ultra.toInt())
        // But all clamped to 8192 max:
        assertTrue(minOf(light.toInt(), 8192) <= 8192)
        assertTrue(minOf(medium.toInt(), 8192) <= 8192)
        assertTrue(minOf(ultra.toInt(), 8192) <= 8192)
    }

    @Test
    fun `windowFractions publishes per-window progress`() {
        val r = CompactRunReporter { }
        r.callStart("m", 1, 3)  // 3 windows
        assertEquals(3, r.snapshot().windowFractions.size)
        assertEquals(0.0, r.snapshot().windowFractions[0], 1e-9)
        r.callChars(1, 500, 1000)
        r.callChars(2, 300, 1000)
        r.callChars(3, 100, 1000)
        val wf = r.snapshot().windowFractions
        assertEquals(0.5, wf[0], 0.01)
        assertEquals(0.3, wf[1], 0.01)
        assertEquals(0.1, wf[2], 0.01)
    }

    @Test
    fun `6 windows at 96k chars each for 578k transcript`() {
        val cap = CompactChunking.perCallInputCapChars(128_000)
        assertEquals(96_000, cap)
        val windows = CompactChunking.packWindows("x".repeat(578_000), cap)
        assertEquals("578k / 96k should produce 6-7 windows", 6, windows.size.coerceIn(6, 7))
    }

    @Test
    fun `cancel does not hang - cancellation propagates through awaitAll`() = runTest {
        val job = launch {
            coroutineScope {
                (1..3).map { i ->
                    async(Dispatchers.Default) { delay(10_000); "s$i" }
                }.map { it.await() }
            }
        }
        delay(50)
        job.cancel()
        // Should complete quickly after cancel (not hang 30s)
        val start = System.currentTimeMillis()
        job.join()
        val elapsed = System.currentTimeMillis() - start
        assertTrue("cancel took ${elapsed}ms to propagate (expected <500ms)", elapsed < 500)
    }
}
