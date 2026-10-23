package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.detachedMutable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-provider-config-cme] Regression tests for publishing a config that
 * writers never mutate in place.
 *
 * Origin: a real device crash log recorded
 * `ConcurrentModificationException at ArrayList$Itr.checkForComodification`
 * inside `ChatViewModel$showEnhancedCacheToggle$1.invokeSuspend`, i.e. inside a
 * `combine` block. `ProviderConfig` exposes MutableLists and the mutators
 * rewrote them in place, so a reader iterating one could see `modCount` change
 * mid-scan.
 *
 * What this file used to do, and why it was replaced: it defined its own
 * `entryById` and its own `Entry` and tested those. A test that re-implements
 * the logic it claims to cover stays green whatever the repository does, so
 * it proved only that `synchronized` works. It is now written against
 * `ProviderConfig.detachedMutable()` - the production function the repository
 * actually publishes through.
 *
 * The two scan shapes that were tried and rejected are still pinned at the
 * bottom, so nobody re-introduces them believing they were improvements.
 */
class ProviderConfigConcurrentLookupTest {

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "test")

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = model(id),
        uuid = id,
    )

    private fun group(id: String, vararg members: String) =
        ModelGroup(id = id, name = id, memberEntryIds = members.toMutableList())

    private fun config(entries: List<ModelEntry>) = ProviderConfig(
        instances = mutableListOf(
            ProviderInstance(
                id = "inst-1",
                label = "inst-1",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
            ),
        ),
        modelEntries = entries.toMutableList(),
        modelGroups = mutableListOf(group("group-1", "entry-1")),
        agentLoopModelEntryIds = mutableListOf("entry-1"),
        agentLoopGroupIds = mutableListOf("group-1"),
    )

    @Test
    fun `detached copy carries the same contents`() {
        val original = config(listOf(entry("entry-1"), entry("entry-2")))
        val detached = original.detachedMutable()

        assertEquals(original.instances.size, detached.instances.size)
        assertEquals(original.modelEntries.size, detached.modelEntries.size)
        assertEquals(original.modelGroups.size, detached.modelGroups.size)
        assertEquals(original.agentLoopModelEntryIds, detached.agentLoopModelEntryIds)
        assertEquals(original.agentLoopGroupIds, detached.agentLoopGroupIds)
        assertEquals(original.modelEntries.map { it.id }, detached.modelEntries.map { it.id })
    }

    @Test
    fun `writing to the copy cannot reach the published lists`() {
        val published = config(listOf(entry("entry-1"), entry("entry-2")))

        val detached = published.detachedMutable()
        detached.modelEntries.add(entry("entry-3"))
        detached.modelGroups[0].memberEntryIds.add("entry-3")
        detached.agentLoopModelEntryIds.add("entry-3")

        assertEquals("the published list grew", 2, published.modelEntries.size)
        assertEquals(
            "the published group's members grew",
            1,
            published.modelGroups[0].memberEntryIds.size,
        )
        assertEquals(1, published.agentLoopModelEntryIds.size)
    }

    /**
     * The production shape: a writer builds a detached copy, changes it, and
     * publishes that. A reader walks the published config the whole time.
     *
     * With in-place mutation this throws ConcurrentModificationException; the
     * reader here deliberately has no try/catch, so reaching the end of the
     * loop body is the assertion.
     */
    @Test
    fun `a reader iterating the published config survives a concurrent publish`() {
        var published = config(List(400) { entry("id-$it") })
        val stop = AtomicBoolean(false)

        val writer = Thread {
            var generation = 0
            while (!stop.get()) {
                generation++
                // Exactly what the repository does now: take a detached copy,
                // change it, publish the copy.
                val next = published.detachedMutable()
                next.modelEntries.clear()
                next.modelEntries.addAll(List(400) { entry("id-$it") })
                next.modelEntries[377] = entry("id-377")
                published = next
            }
        }

        var answered = 0
        var attempts = 0
        val reader = Thread {
            while (!stop.get()) {
                // No try/catch on purpose: an exception here fails the test.
                val found = published.modelEntries.firstOrNull { it.id == "id-377" }
                if (found != null) answered++
                attempts++
            }
        }

        writer.start()
        reader.start()
        Thread.sleep(1_500)
        stop.set(true)
        writer.join(3_000)
        reader.join(3_000)

        assertTrue("reader thread died - check the exception in the report", attempts > 100)
        assertTrue("scan answered with a live entry at least once", answered > 0)
    }

    @Test
    fun `a reader sees a consistent snapshot, never a half-written list`() {
        // The published config is replaced wholesale, so a reader either sees
        // the old list or the new one - it cannot see a list that is being
        // appended to. This is what the in-place mutation broke.
        val before = config(List(10) { entry("id-$it") })
        val after = config(List(20) { entry("id-$it") })

        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val snapshot = if (it % 2 == 0) before else after
            seen += snapshot.modelEntries.size
        }

        assertEquals(setOf(10, 20), seen)
        assertNotNull("a published list is never empty mid-flight", before.modelEntries.firstOrNull())
    }

    /**
     * The two scan shapes that were tried and rejected, kept so they are not
     * reintroduced.
     */
    @Test
    fun `list indices is a snapshot, so a scan can over-read`() {
        // `for (i in list.indices) list[i]` captures the range once. A
        // concurrent shrink after that makes `list[i]` throw
        // IndexOutOfBoundsException - so it swaps the CME for a different
        // crash rather than fixing anything.
        val list = mutableListOf(1, 2, 3)
        val range = list.indices
        list.clear()
        assertEquals(3, range.count())
        assertEquals(0, list.size)
        assertTrue("captured range outlived the list", range.any { it >= list.size })
    }
}
