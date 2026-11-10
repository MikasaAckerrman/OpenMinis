package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [M-R3.3] The RPC routing decision + rendering logic of SearchTools —
 * the pure layer that decides which grep/glob calls go to the rust
 * daemon and how its answers are re-rendered into the rg-shaped text the
 * model has always seen. The gates are the correctness firewall: routing
 * a regex pattern to the daemon's LITERAL substring search would silently
 * return wrong results, so every non-mappable args shape must refuse the
 * RPC path and keep the local rg semantics.
 */
class SearchToolsRpcTest {

    @Test
    fun `literal patterns pass, regex metachars refuse`() {
        assertTrue(SearchTools.isLiteralPattern("квант_гоночный"))
        assertTrue(SearchTools.isLiteralPattern("hello world 42"))
        assertFalse(SearchTools.isLiteralPattern("foo.bar"))
        assertFalse(SearchTools.isLiteralPattern("a|b"))
        assertFalse(SearchTools.isLiteralPattern("task\\d+"))
        assertFalse(SearchTools.isLiteralPattern("(green|blue)"))
        assertFalse(SearchTools.isLiteralPattern("[a-z]+"))
    }

    @Test
    fun `grep rpc eligibility refuses unmappable args shapes`() {
        fun args(vararg pairs: Pair<String, Any>): JSONObject =
            JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

        // Plain literal: the daemon path.
        assertTrue(SearchTools.grepRpcEligible(args("pattern" to "needle")))

        // Regex / context / pcre / glob-filter: rg-only semantics.
        assertFalse(SearchTools.grepRpcEligible(args("pattern" to "foo.*bar")))
        assertFalse(SearchTools.grepRpcEligible(args("pattern" to "ok", "context" to 3)))
        assertFalse(SearchTools.grepRpcEligible(args("pattern" to "ok", "pcre" to true)))
        assertFalse(SearchTools.grepRpcEligible(args("pattern" to "ok", "glob" to "*.kt")))
    }

    @Test
    fun `glob rpc eligibility refuses braces and bracket classes`() {
        fun args(pattern: String): JSONObject = JSONObject().put("pattern", pattern)

        assertTrue(SearchTools.globRpcEligible(args("*.kt")))
        assertTrue(SearchTools.globRpcEligible(args("src/**/*.toml")))
        assertFalse(SearchTools.globRpcEligible(args("*.{kt,java}")))
        assertFalse(SearchTools.globRpcEligible(args("test[0-9].py")))
        assertFalse(SearchTools.globRpcEligible(args("")))
    }

    @Test
    fun `workspace-relative translation and refusal outside the workspace`() {
        assertEquals("src/x.kt", SearchTools.workspaceRelative("/var/minis/workspace/src/x.kt"))
        assertEquals("", SearchTools.workspaceRelative("/var/minis/workspace"))
        // The bare /var/minis default IS the session workspace (resolveHost
        // maps it there) — the daemon root, empty relative path.
        assertEquals("", SearchTools.workspaceRelative("/var/minis"))
        assertEquals("", SearchTools.workspaceRelative("/var/minis/"))
        // Outside the workspace: the daemon serves ONE root; those paths
        // keep the local rg path with the exact pre-M-R3.3 semantics.
        assertNull(SearchTools.workspaceRelative("/var/minis/shared/tg"))
        assertNull(SearchTools.workspaceRelative("/etc/hostname"))
        // Relative guest paths resolve to /var/minis/<rel>, NOT the
        // workspace — preserved verbatim (no silent semantics change).
        assertNull(SearchTools.workspaceRelative("src"))
    }

    private fun grepEnvelope(vararg matches: String, truncated: Boolean = false): JSONObject {
        val arr = JSONArray()
        matches.forEach { m ->
            val parts = m.split(":", limit = 3)
            arr.put(
                JSONObject()
                    .put("file", parts[0])
                    .put("line", parts[1].toInt())
                    .put("text", parts.getOrElse(2) { "" }),
            )
        }
        return JSONObject()
            .put("matches", arr)
            .put("files_matched", 1)
            .put("files_scanned", 3)
            .put("truncated", truncated)
    }

    @Test
    fun `grep envelope renders rg-shaped lines`() {
        val out = SearchTools.formatGrepResult(
            grepEnvelope("src/a.kt:10:settle(call, outcome)", "src/b.rs:2:fn main()"),
            maxResults = 200, filesOnly = false,
        )
        assertEquals(
            "src/a.kt:10:settle(call, outcome)\nsrc/b.rs:2:fn main()",
            out,
        )
    }

    @Test
    fun `files-only envelope dedupes to the -l shape`() {
        val out = SearchTools.formatGrepResult(
            grepEnvelope("src/a.kt:1:x", "src/a.kt:5:y", "src/b.rs:2:z"),
            maxResults = 200, filesOnly = true,
        )
        assertEquals("src/a.kt\nsrc/b.rs", out)
    }

    @Test
    fun `daemon hit cap surfaces as an explicit note not a silent cut`() {
        val out = SearchTools.formatGrepResult(
            grepEnvelope("a.kt:1:x", "a.kt:2:y", "a.kt:3:z", truncated = true),
            maxResults = 200, filesOnly = false,
        )
        assertTrue(out.startsWith("a.kt:1:x\na.kt:2:y\na.kt:3:z\n"))
        assertTrue(out.contains("more matches exist"))
    }

    @Test
    fun `max_results slices the rendered lines`() {
        val out = SearchTools.formatGrepResult(
            grepEnvelope("a.kt:1:x", "a.kt:2:y", "a.kt:3:z"),
            maxResults = 2, filesOnly = false,
        )
        assertEquals("a.kt:1:x\na.kt:2:y", out)
    }

    @Test
    fun `empty and malformed envelopes degrade to no matches`() {
        assertEquals("(no matches)", SearchTools.formatGrepResult(JSONObject(), 200, false))
        assertEquals(
            "(no matches)",
            SearchTools.formatGrepResult(grepEnvelope(), 200, false),
        )
    }

    @Test
    fun `glob envelope renders newline-joined paths with truncation note`() {
        val envelope = JSONObject()
            .put("matches", JSONArray().put("src/a.kt").put("lib/b.kt"))
            .put("files_scanned", 9)
            .put("truncated", true)
        val out = SearchTools.formatGlobResult(envelope, 200)
        assertEquals("src/a.kt\nlib/b.kt\n(more paths match; refine the pattern)", out)

        assertEquals(
            "src/a.kt\nlib/b.kt",
            SearchTools.formatGlobResult(
                envelope.put("truncated", false), 200,
            ),
        )
        assertEquals("(no matches)", SearchTools.formatGlobResult(JSONObject(), 200))
    }
}
