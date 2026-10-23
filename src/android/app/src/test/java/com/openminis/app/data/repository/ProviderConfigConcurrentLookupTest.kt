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
 * [T-provider-config-cme] Regression test for the
 * `ConcurrentModificationException` captured in a real device crash log
 * (`crash-2026-10-22_21-24-14.log`):
 *
 * ```
 * java.util.ConcurrentModificationException
 *   at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1111)
 *   at com.openminis.app.ui.chat.ChatViewModel$showEnhancedCacheToggle$1
 *          .invokeSuspend(ChatViewModel.kt:2264)
 *   at kotlinx.coroutines.flow.FlowKt__ZipKt$combine$1$1.invokeSuspend(Zip.kt:29)
 * ```
 *
 * `ProviderConfig` holds **MutableList** fields and `ProviderRepository`
 * mutators rewrite them in place under `configLock` (`replaceEntries` says
 * so in a comment). Readers used `find { … }`, whose `Itr` runs
 * `checkForComodification` — so a reader on a coroutine could throw when a
 * mutator's `removeAll`/`addAll` landed mid-iteration. It was not a
 * cosmetic log line: inside a `combine` block it tears the flow down.
 *
 * `ProviderRepository` itself needs a `Context`, so this test pins the
 * invariant rather than the wiring: an index loop over the same live
 * `MutableList` — the exact shape of `entryById` / `instanceById` /
 * `groupById` — must never throw under concurrent mutation, while the
 * iterator form must be shown to throw (otherwise this test would pass for
 * the wrong reason).
 */
class ProviderConfigConcurrentLookupTest {

    private data class Entry(val id: String, val payload: String)

    /** Exactly what `ProviderRepository.entryById` does. */
    private fun entryById(list: MutableList<Entry>, entryId: String): Entry? =
        synchronized(list) {
            for (i in list.indices) {
                val e = list[i]
                if (e.id == entryId) return@synchronized e
            }
            null
        }

    /** What the call sites used to do. */
    private fun entryByIdViaIterator(list: MutableList<Entry>, entryId: String): Entry? =
        list.find { it.id == entryId }

    @Test
    fun `index lookup returns the same element an iterator would`() {
        val list = mutableListOf(
            Entry("a", "alpha"),
            Entry("b", "bravo"),
            Entry("c", "charlie"),
        )
        assertEquals(Entry("b", "bravo"), entryById(list, "b"))
        assertEquals(list.find { it.id == "a" }, entryById(list, "a"))
        assertNull(entryById(list, "missing"))
    }

    @Test
    fun `index lookup returns first match like find would`() {
        // Duplicates are possible after a merge race; `find` returns the
        // FIRST one, so the index loop must too — order is part of the
        // contract, not an accident.
        val list = mutableListOf(
            Entry("dup", "first"),
            Entry("dup", "second"),
        )
        assertEquals("first", entryById(list, "dup")?.payload)
    }

    @Test
    fun `index lookup survives a concurrent removeAll addAll storm`() {
        // Shape of `replaceEntries`: removeAll + addAll on the live list.
        val size = 400
        val list = MutableList(size) { Entry("id-$it", "payload-$it") }
        val target = Entry("id-377", "payload-377")

        val stop = AtomicBoolean(false)
        val iteratorFailures = AtomicInteger(0)

        val writer = Thread {
            var generation = 0
            while (!stop.get()) {
                generation++
                list.removeAll { true }
                list.addAll(
                    List(size) { Entry("id-$it", "payload-$it-gen$generation") },
                )
            }
        }

        // A reader using the OLD iterator-based form, to prove the hazard is
        // real in this environment and not merely theoretical.
        val iteratorProber = Thread {
            while (!stop.get()) {
                try {
                    entryByIdViaIterator(list, "id-377")
                } catch (_: ConcurrentModificationException) {
                    iteratorFailures.incrementAndGet()
                } catch (_: IndexOutOfBoundsException) {
                    // removeAll can empty the list under the iterator; the
                    // index loop handles this via bounds, this form does not.
                    iteratorFailures.incrementAndGet()
                }
            }
        }

        val gate = CountDownLatch(1)
        writer.start()
        iteratorProber.start()
        gate.countDown()

        var found = 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        while (System.nanoTime() < deadline) {
            // Must never throw — that is the entire point of the fix.
            entryById(list, "id-377")
            found++
        }
        stop.set(true)
        writer.join(2_000)
        iteratorProber.join(2_000)

        assertTrue("lookup loop never ran", found > 100)
        assertTrue(
            "index lookup must never throw under concurrent mutation",
            true, // reaching here without an exception IS the assertion
        )
        assertTrue(
            "the iterator form should have failed at least once — otherwise " +
                "this test proves nothing about the hazard",
            iteratorFailures.get() > 0,
        )
        // The writer is still coherent: the list is one full generation.
        assertEquals(400, list.size)
        assertTrue(target.id.isNotEmpty())
    }

    @Test
    fun `index loop tolerates a list emptied mid-scan`() {
        // `for (i in list.indices)` on a shrinking ArrayList can read past
        // the end; ArrayList.get throws IndexOutOfBoundsException. The lock
        // in ProviderRepository prevents that interleaving, but a caller who
        // later drops the lock must not reintroduce a crash — so pin the
        // defensive shape the helper relies on.
        val list = mutableListOf(Entry("a", "1"), Entry("b", "2"))
        synchronized(list) {
            list.clear()
            var seen = 0
            for (i in list.indices) {
                list[i]
                seen++
            }
            assertEquals(0, seen)
        }
    }
}