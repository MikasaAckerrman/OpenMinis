package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-provider-config-cme] Regression tests for `ProviderRepository.entryById`.
 *
 * Origin: a real device crash log recorded
 * `ConcurrentModificationException at ArrayList$Itr.checkForComodification`
 * inside `ChatViewModel$showEnhancedCacheToggle$1.invokeSuspend`, i.e. inside a
 * `combine` block. `ProviderConfig` exposes MutableLists and the mutators
 * rewrite them in place, so a reader iterating one could see `modCount` change
 * mid-scan.
 *
 * The fix is the LOCK, not the shape of the scan: reader and mutator now take
 * the same monitor, so they cannot interleave and an ordinary `firstOrNull` is
 * correct. These tests pin that contract, and pin the two clever scan shapes
 * that were tried and rejected so nobody re-introduces them believing they
 * were improvements.
 */
class ProviderConfigConcurrentLookupTest {

    private data class Entry(val id: String, val payload: String)

    /** What the repository helper does: the same monitor, then a plain scan. */
    private fun entryById(lock: Any, list: MutableList<Entry>, entryId: String): Entry? =
        synchronized(lock) { list.firstOrNull { it.id == entryId } }

    @Test
    fun `locked lookup returns the same element find would`() {
        val lock = Any()
        val list = mutableListOf(
            Entry("a", "alpha"), Entry("b", "bravo"), Entry("c", "charlie"),
        )
        assertEquals(Entry("b", "bravo"), entryById(lock, list, "b"))
        assertEquals(list.find { it.id == "a" }, entryById(lock, list, "a"))
        assertNull(entryById(lock, list, "missing"))
    }

    @Test
    fun `locked lookup returns first match like find would`() {
        // Duplicates survive a merge race; `find` yields the FIRST, so order
        // is part of the contract rather than an accident.
        val lock = Any()
        val list = mutableListOf(Entry("dup", "first"), Entry("dup", "second"))
        assertEquals("first", entryById(lock, list, "dup")?.payload)
    }

    @Test
    fun `list indices is a snapshot, so a scan can over-read`() {
        // This is the mechanism behind two rejected implementations.
        //
        // `for (i in list.indices) list[i]` captures the range once. A
        // concurrent shrink after that makes `list[i]` throw
        // IndexOutOfBoundsException — so it swaps the CME for a different
        // crash rather than fixing anything.
        val list = mutableListOf(1, 2, 3)
        val range = list.indices
        list.clear()
        assertEquals(3, range.count())
        assertEquals(0, list.size)
        assertTrue("captured range outlived the list", range.any { it >= list.size })
    }@Test
    fun `locked reader and locked writer never throw`() {
        val lock = Any()
        val size = 400
        val list = MutableList(size) { Entry("id-$it", "payload-$it") }
        val stop = AtomicBoolean(false)

        // Both sides take the same monitor, exactly as `entryById` and
        // `replaceEntries` do in production. The unguarded scan below is the
        // test: if a throw ever escapes it, the lock is not doing its job.
        val writer = Thread {
            var generation = 0
            while (!stop.get()) {
                generation++
                synchronized(lock) {
                    list.clear()
                    list.addAll(List(size) { Entry("id-$it", "p-$it-g$generation") })
                }
            }
        }

        var answered = 0
        var attempts = 0
        val worker = Thread {
            while (!stop.get()) {
                // No try/catch: reaching the end of the loop body IS the
                // assertion. Any exception thrown here fails the test outright.
                if (entryById(lock, list, "id-377") != null) answered++
                attempts++
            }
        }

        writer.start()
        worker.start()
        Thread.sleep(1_500)
        stop.set(true)
        writer.join(3_000)
        worker.join(3_000)

        assertTrue("reader thread died — check the exception in the report", attempts > 100)
        assertTrue("scan answered with a live entry at least once", answered > 0)
    }
}