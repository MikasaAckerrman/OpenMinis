package com.openminis.app.debug

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-discover-sync] The registry's own KDoc: "Keep this in sync with the
 * dispatch `when` in DebugRPCHandler.dispatch."
 *
 * Fourteen methods were dispatchable all along and missing from rpc.discover,
 * including debug.shellExecute and the whole debug.update.* trio, so a
 * client introspecting the API had no way to know they existed.
 *
 * Full two-way comparison needs the handler source at test runtime, which a
 * plain JVM test cannot reach, so this pins the method names that were
 * actually missing. A new undocumented method would still slip through; what
 * this guarantees is that these fourteen cannot silently disappear again,
 * which is how they were found in the first place.
 */
class DiscoverSyncTest {

    private val previouslyUndocumented = listOf(
        "debug.rawLs",
        "debug.shellExecute",
        "debug.update.check",
        "debug.update.download",
        "debug.update.install",
        "debug.engineSwap",
        "debug.jankStats",
        "debug.jankReset",
        "debug.memoryFts",
        "debug.permissions.list",
        "debug.minisConfig.exec",
        "debug.deviceState",
        "chat.session.rescue",
        "chat.uiPrompt",
    )

    private fun advertised(): Set<String> {
        val arr = DebugMethodRegistry.discover().getJSONArray("methods")
        return (0 until arr.length()).mapTo(HashSet()) { arr.getJSONObject(it).optString("name") }
    }

    @Test
    fun everyPreviouslyUndocumentedMethodIsAdvertised() {
        val names = advertised()
        val missing = previouslyUndocumented.filterNot { it in names }
        assertTrue(
            "rpc.discover no longer advertises: ${missing.joinToString()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyAdvertisedSpecHasANonEmptyDescription() {
        val arr = DebugMethodRegistry.discover().getJSONArray("methods")
        val undescribed = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optString("description").isBlank() }
            .map { it.optString("name") }
        assertTrue(
            "methods advertised with an empty description: ${undescribed.joinToString()}",
            undescribed.isEmpty(),
        )
    }

    @Test
    fun everyAdvertisedSpecDeclaresItsReturnShape() {
        val arr = DebugMethodRegistry.discover().getJSONArray("methods")
        val untyped = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optString("returns").isBlank() }
            .map { it.optString("name") }
        assertTrue(
            "methods advertised without a 'returns' shape: ${untyped.joinToString()}",
            untyped.isEmpty(),
        )
    }
}