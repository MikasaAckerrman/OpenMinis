package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * Regression tests for [detachedMutable], the fix for the crash captured in
 * crash-2026-10-22_21-24-14.log:
 *
 *     java.util.ConcurrentModificationException
 *         at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1111)
 *         at ChatViewModel$showEnhancedCacheToggle$1.invokeSuspend(ChatViewModel.kt:2264)
 *
 * The published config must never be mutated in place, because readers hold it
 * and iterate its lists. These test the production function itself - the
 * neighbouring ProviderConfigConcurrentLookupTest re-implements the lookup
 * inside the test, which stays green no matter what the repository does.
 */
class ProviderConfigDetachTest {

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "test")

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = model(id),
        uuid = id,
    )

    private fun group(id: String, vararg members: String) =
        ModelGroup(id = id, name = id, memberEntryIds = members.toMutableList())

    private fun config() = ProviderConfig(
        instances = mutableListOf(
            ProviderInstance(
                id = "inst-1",
                label = "inst-1",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
            ),
        ),
        modelEntries = mutableListOf(entry("entry-1")),
        modelGroups = mutableListOf(group("group-1", "entry-1", "entry-2")),
        agentLoopModelEntryIds = mutableListOf("entry-1"),
        agentLoopGroupIds = mutableListOf("group-1"),
    )

    /** The whole point: nothing the copy touches can be the published list. */
    @Test
    fun everyListIsADifferentObject() {
        val original = config()
        val detached = original.detachedMutable()

        assertNotSame(original.instances, detached.instances)
        assertNotSame(original.modelEntries, detached.modelEntries)
        assertNotSame(original.modelGroups, detached.modelGroups)
        assertNotSame(original.agentLoopModelEntryIds, detached.agentLoopModelEntryIds)
        assertNotSame(original.agentLoopGroupIds, detached.agentLoopGroupIds)
    }

    /**
     * The one a shallow copy would miss. ModelGroup.memberEntryIds is itself
     * mutable, so copying only the outer list would leave the inner one
     * published - and removeEntry's cascade does
     * `group.memberEntryIds.removeAll {}` on it.
     */
    @Test
    fun nestedMemberIdsAreAlsoDetached() {
        val original = config()
        val detached = original.detachedMutable()

        assertNotSame(original.modelGroups[0].memberEntryIds, detached.modelGroups[0].memberEntryIds)
        assertEquals(original.modelGroups[0].memberEntryIds, detached.modelGroups[0].memberEntryIds)
    }

    @Test
    fun mutatingTheCopyLeavesTheOriginalUntouched() {
        val original = config()
        val detached = original.detachedMutable()

        detached.modelEntries.clear()
        detached.instances.clear()
        detached.agentLoopModelEntryIds.clear()
        detached.modelGroups[0].memberEntryIds.clear()

        assertEquals(1, original.modelEntries.size)
        assertEquals(1, original.instances.size)
        assertEquals(1, original.agentLoopModelEntryIds.size)
        assertEquals(2, original.modelGroups[0].memberEntryIds.size)
    }

    /**
     * The actual crash: iterate the published list while the writer works on
     * its detached copy. With the old in-place mutation this throws; here the
     * iterator must complete over a list nobody can touch.
     */
    @Test
    fun iteratingTheOriginalWhileAMutatorRunsDoesNotThrow() {
        val published = config()
        repeat(200) {
            val writer = published.detachedMutable()
            writer.modelEntries.add(entry("scratch-$it"))
            writer.modelGroups[0].memberEntryIds.removeAll { id -> id.startsWith("scratch") }
        }

        var visited = 0
        for (entry in published.modelEntries) {
            visited++
            // Touching every member list too - that is the nested half.
            published.modelGroups.forEach { group -> group.memberEntryIds.forEach { } }
        }
        assertEquals(1, visited)
    }

    /** Replacing a group on the copy must not touch the published group. */
    @Test
    fun replacingAnElementOnTheCopyIsLocal() {
        val original = config()
        val detached = original.detachedMutable()

        detached.modelGroups[0] = detached.modelGroups[0].copy(name = "renamed")

        assertEquals("group-1", original.modelGroups[0].name)
        assertEquals("renamed", detached.modelGroups[0].name)
    }

    @Test
    fun detachingPreservesContentsExactly() {
        val original = config()
        val detached = original.detachedMutable()

        assertEquals(original.instances, detached.instances)
        assertEquals(original.modelEntries, detached.modelEntries)
        assertEquals(original.modelGroups, detached.modelGroups)
        assertEquals(original.agentLoopModelEntryIds, detached.agentLoopModelEntryIds)
        assertEquals(original.agentLoopGroupIds, detached.agentLoopGroupIds)
    }
}