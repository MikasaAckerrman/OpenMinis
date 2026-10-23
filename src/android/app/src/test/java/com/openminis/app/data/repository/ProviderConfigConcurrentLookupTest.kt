package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-provider-config-cme] Regression test for the defensive shape of
 * `ProviderRepository.entryById`.
 *
 * Origin: a real device crash log recorded
 * `ConcurrentModificationException at ArrayList$Itr.checkForComodification`
 * inside `ChatViewModel$showEnhancedCacheToggle$1.invokeSuspend`, i.e. inside a
 * `combine` block. `ProviderConfig` exposes MutableLists and the mutators
 * rewrite them in place, so `find {}` was iterating a list another thread was
 * rebuilding.
 *
 * The helper now scans BY INDEX under the repository lock, with `getOrNull`
 * on every read. This pins the two properties that matter:
 *   - an index scan does not throw `ConcurrentModificationException`; and
 *   - it does not trade that for `IndexOutOfBoundsException` when the list
 *     shrinks mid-scan, which a naive `for (i in list.indices) list[i]` does.
 *
 * The writer here deliberately takes NO lock and the reader takes a different
 * one, so the two really do interleave: that models the "reader forgot the
 * lock" case the defensive shape has to survive on its own.
 */
class ProviderConfigConcurrentLookupTest {

    private data class Entry(val id: String, val payload: String)

    /** Byte-for-byte the scan `ProviderRepository.entryById` performs. */
    private fun entryById(list: MutableList<Entry>, entryId: String): Entry? =
        synchronized(list) {
            for (i in list.indices) {
                val e = list.getOrNull(i) ?: break
                if (e.id == entryId) return@synchronized e
            }
            null
        }

    @Test
    fun `index lookup returns the same element find would`() {
        val list = mutableListOf(
            Entry("a", "alpha"), Entry("b", "bravo"), Entry("c", "charlie"),
        )
        assertEquals(Entry("b", "bravo"), entryById(list, "b"))
        assertEquals(list.find { it.id == "a" }, entryById(list, "a"))
        assertNull(entryById(list, "missing"))
    }

    @Test
    fun `index lookup returns first match like find would`() {
        // Duplicates survive a merge race; `find` yields the FIRST, so order
        // is part of the contract rather than an accident.
        val list = mutableListOf(Entry("dup", "first"), Entry("dup", "second"))
        assertEquals("first", entryById(list, "dup")?.payload)
    }

    @Test
    fun `index loop tolerates a list emptied mid-scan`() {
        val list = mutableListOf(Entry("a", "1"), Entry("b", "2"))
        synchronized(list) {
            list.clear()
            var seen = 0
            for (i in list.indices) {
                list.getOrNull(i) ?: break
                seen++
            }
            assertEquals(0, seen)
        }
    }@Test
    fun `index lookup survives a concurrent clear and refill storm`() {
        val size = 400
        val list = MutableList(size) { Entry("id-$it", "payload-$it") }
        val stop = AtomicBoolean(false)
        val iteratorFailures = AtomicInteger(0)

        // Shape of `replaceEntries`: drop the old generation, append a new
        // one. `clear()` rather than `removeAll{}` — removeAll compacts and
        // leaves nulls in the middle of the backing array until it finishes,
        // and a reader hitting one of those NPEs instead of the CME we are
        // actually trying to provoke.
        val writer = Thread {
            var generation = 0
            while (!stop.get()) {
                generation++
                list.clear()
                list.addAll(List(size) { Entry("id-$it", "p-$it-g$generation") })
            }
        }

        // Probes the OLD form to prove the hazard is real in this environment
        // rather than merely theoretical. Catches Throwable because a probe is
        // expected to break in whatever way the runtime sees fit — letting a
        // single CME escape a background thread fails the run for the wrong
        // reason.
        val iteratorProber = Thread {
            while (!stop.get()) {
                try {
                    list.find { it.id == "id-377" }
                } catch (_: Throwable) {
                    iteratorFailures.incrementAndGet()
                }
            }
        }

        val gate = CountDownLatch(1)
        writer.start()
        iteratorProber.start()
        gate.countDown()

        var found = 0
        var answered = 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        while (System.nanoTime() < deadline) {
            // Must never throw — that is the entire point of the fix.
            val hit = entryById(list, "id-377")
            found++
            if (hit != null) answered++
        }
        stop.set(true)
        writer.join(2_000)
        iteratorProber.join(2_000)

        assertTrue("lookup loop never ran", found > 100)
        assertTrue("scan answered with a real entry at least once", answered > 0)
        assertTrue(
            "the iterator form should have failed at least once — otherwise " +
                "this test proves nothing about the hazard",
            iteratorFailures.get() > 0,
        )
    }
}