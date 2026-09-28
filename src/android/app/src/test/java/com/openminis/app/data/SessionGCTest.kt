package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-session-gc] The GC contract pinned: what is selectable, what is
 * untouchable, what the rewrite does. Pure JVM — the executor wiring is
 * compile-checked; these are the safety rules.
 */
class SessionGCTest {

    private fun trRow(
        id: String,
        toolUseId: String = "toolu_$id",
        output: String = "x".repeat(2000),
        success: Boolean = true,
        sortOrder: Long = 0,
    ): SessionGC.Row {
        val pj = """[{"type":"toolResult","value":{"toolUseId":"$toolUseId","name":"shell_execute","output":${jsonStr(output)},"success":$success,"snapshot":{"type":"text","text":"tail"}}}]"""
        return SessionGC.Row(id, "user", sortOrder, pj)
    }

    private fun userRow(id: String, text: String = "hello", sortOrder: Long = 0): SessionGC.Row =
        SessionGC.Row(id, "user", sortOrder, """[{"type":"text","value":${jsonStr(text)}}]""")

    private fun assistantRow(id: String, sortOrder: Long = 0): SessionGC.Row =
        SessionGC.Row(id, "assistant", sortOrder, """[{"type":"text","value":"reply"}]""")

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `fat old tool results are candidates, tail and errors are not`() {
        // 8 user turns + interleaved fat tool rows: only the OLD fat rows
        // below the 6-turn protected tail are candidates.
        val rows = mutableListOf<SessionGC.Row>()
        var order = 0L
        repeat(8) { i ->
            rows += userRow("u$i", sortOrder = order++)
            rows += assistantRow("a$i", sortOrder = order++)
            rows += trRow("t$i", sortOrder = order++)
        }
        val candidates = SessionGC.selectCandidates(rows)
        // The protected tail covers the last 6 user turns (u2..u7 span in
        // this layout) — only the earliest fat rows are eligible.
        assertTrue("expected some old candidates, got ${candidates.size}", candidates.size in 1..2)
        // The LAST fat row (t7) is inside the protected tail — never.
        assertTrue(candidates.none { it.messageId == "t7" })
    }

    @Test
    fun `failed tool results are never candidates`() {
        val rows = listOf(userRow("u0"), trRow("t0", success = false), assistantRow("a0"))
        assertTrue(SessionGC.selectCandidates(rows).isEmpty())
    }

    @Test
    fun `already offloaded parts are skipped (idempotent)`() {
        val offloaded = "[CONTEXT OFFLOADED] Content saved to: /var/minis/offloads/tools/x.txt"
        val rows = listOf(userRow("u0"), trRow("t0", output = offloaded + "x".repeat(2000)))
        assertTrue(SessionGC.selectCandidates(rows).isEmpty())
    }

    @Test
    fun `thin results below the threshold are skipped`() {
        val rows = listOf(userRow("u0"), trRow("t0", output = "short"))
        assertTrue(SessionGC.selectCandidates(rows).isEmpty())
    }

    @Test
    fun `rewrite replaces output with the stub and keeps the shape parseable`() {
        val row = trRow("t0")
        val stub = "[CONTEXT OFFLOADED] Content (5000 bytes) saved to: /var/minis/offloads/tools/f.txt"
        val rewritten = SessionGC.rewritePart(row.partsJson!!, "toolu_t0", stub)
        assertNotNull(rewritten)
        val arr = org.json.JSONArray(rewritten!!)
        val value = arr.getJSONObject(0).getJSONObject("value")
        assertEquals(stub, value.getString("output"))
        assertEquals("toolu_t0", value.getString("toolUseId"))
        assertTrue(value.getBoolean("success"))
        // snapshot digest survives untouched
        assertEquals("tail", value.getJSONObject("snapshot").getString("text"))
    }

    @Test
    fun `rewrite refuses drifted invariants (thin, failed, offloaded, missing id)`() {
        val stub = "[CONTEXT OFFLOADED] s"
        assertNull(SessionGC.rewritePart(trRow("t0").partsJson!!, "other_id", stub)) // id gone
        assertNull(SessionGC.rewritePart(trRow("t0", output = "short").partsJson!!, "toolu_t0", stub)) // now thin
        assertNull(SessionGC.rewritePart(trRow("t0", success = false).partsJson!!, "toolu_t0", stub)) // failed
        val offloaded = "[CONTEXT OFFLOADED] " + "x".repeat(2000)
        assertNull(SessionGC.rewritePart(trRow("t0", output = offloaded).partsJson!!, "toolu_t0", stub))
    }

    @Test
    fun `weigh reports tool share and offloadable savings`() {
        val rows = listOf(
            userRow("u0", text = "hello"),
            trRow("t0", output = "x".repeat(2000)),
            assistantRow("a0"),
        )
        val w = SessionGC.weigh(rows, emptyList())
        assertEquals(3, w.rows)
        assertEquals(2000, w.toolResultChars)
        assertTrue(w.offloadableChars >= 0)
    }

    @Test
    fun `short sessions are fully protected (boundary -1)`() {
        val rows = listOf(userRow("u0"), assistantRow("a0"), trRow("t0"))
        assertEquals(-1, SessionGC.gcBoundary(rows))
        assertTrue(SessionGC.selectCandidates(rows).isEmpty())
    }
}
