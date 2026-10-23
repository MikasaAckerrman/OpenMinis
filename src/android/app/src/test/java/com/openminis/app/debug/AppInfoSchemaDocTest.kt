package com.openminis.app.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-appinfo-schema-doc] `debug.appInfo` documents its response shape as a
 * string in the registry, and `rpc.discover` hands that string to every
 * client. The bounded-walk fix for dirSize renamed `diskUsage.filesDir` to
 * `filesDirExclSubtrees` and added `truncated`, `maxDepth` and `maxEntries` —
 * and left the documented shape untouched, so a client reading
 * `diskUsage.filesDir` got null with no error anywhere.
 *
 * There is no Robolectric in this project, so the handler itself cannot be
 * invoked from a unit test. The documented shape is the part that CAN be
 * checked, and it is exactly the part that drifted. Tested through
 * [DebugMethodRegistry.discover] rather than the internal list, because that
 * JSON is what a client actually reads.
 */
class AppInfoSchemaDocTest {

    private fun returnsOf(method: String): String {
        val d: JSONObject = DebugMethodRegistry.discover()
        val arr = d.getJSONArray("methods")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") == method) return o.optString("returns")
        }
        throw AssertionError("$method missing from rpc.discover")
    }

    @Test
    fun leakCountersAreDocumentedSoTheContractStaysCheckable() {
        // [T-android-leak-measure] These are the numbers that verify EC-1,
        // EC-2 and SE-1 on a device. If the documented shape stops matching
        // the response, the verification silently reads null instead of a
        // count - which is the failure this whole class of bug produces.
        val doc = returnsOf("debug.appInfo")
        for (key in listOf(
            "liveShells",
            "liveShellSessions",
            "liveShellReaderThreads",
            "liveShellExecuteProcesses",
        )) {
            assertTrue(
                "debug.appInfo no longer documents shellLeaks.$key",
                doc.contains(key),
            )
        }
    }

    @Test
    fun documentedDiskUsageKeysMatchTheBoundedWalkImplementation() {
        val doc = returnsOf("debug.appInfo")
        for (key in listOf(
            "filesDirExclSubtrees",
            "sessions",
            "global",
            "truncated",
            "maxDepth",
            "maxEntries",
            "entriesVisited",
        )) {
            assertTrue(
                "debug.appInfo no longer documents diskUsage.$key",
                doc.contains(key),
            )
        }
    }

    @Test
    fun theRemovedKeyIsNotStillAdvertised() {
        // filesDir was the un-excluded total, which double-counted
        // minis-global because that tree is walked separately. Advertising it
        // again would invite a client to read a key that no longer exists.
        assertFalse(
            "diskUsage.filesDir is gone from the response but still documented",
            returnsOf("debug.appInfo").contains("diskUsage:{filesDir,"),
        )
    }

    @Test
    fun discoverReportsItsOwnMethodCountHonestly() {
        val d = DebugMethodRegistry.discover()
        val declared = d.optInt("methodCount", -1)
        val actual = d.getJSONArray("methods").length()
        assertEquals(
            "rpc.discover advertises a methodCount that does not match the array",
            actual,
            declared,
        )
    }

    @Test
    fun everyAdvertisedMethodNameIsUnique() {
        val arr = DebugMethodRegistry.discover().getJSONArray("methods")
        val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
        assertEquals("duplicate method names in the registry", names.size, names.toSet().size)
    }
}