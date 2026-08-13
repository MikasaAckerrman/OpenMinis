package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-message-surgery] The invariant under test is the provider protocol, not
 * cosmetics: after any single-message delete, every surviving tool_use must
 * still have a matching tool_result and vice versa. A plan that breaks that
 * produces a session which cannot talk to the model at all.
 */
class MessageSurgeryTest {

    private fun text(v: String) = """{"type":"text","value":${quote(v)}}"""
    private fun toolUse(id: String, name: String = "shell_execute") =
        """{"type":"toolUse","value":{"toolUseId":"$id","name":"$name","input":"{}"}}"""
    private fun toolResult(id: String, out: String = "ok", success: Boolean = true) =
        """{"type":"toolResult","value":{"toolUseId":"$id","name":"shell_execute","output":${quote(out)},"success":$success}}"""

    private fun quote(s: String) = org.json.JSONObject.quote(s)
    private fun parts(vararg p: String) = "[" + p.joinToString(",") + "]"

    private fun msg(id: String, role: String, partsJson: String, order: Int) =
        MessageSurgery.Msg(id, role, partsJson, order)

    /** Assert tool pairing holds across the surviving history. */
    private fun assertWellFormed(msgs: List<MessageSurgery.Msg>) {
        val uses = LinkedHashSet<String>()
        val results = LinkedHashSet<String>()
        for (m in msgs) {
            uses.addAll(MessageSurgery.toolUseIds(m.partsJson))
            results.addAll(MessageSurgery.toolResultIds(m.partsJson))
        }
        assertEquals("unanswered tool_use ids", emptySet<String>(), uses - results)
        assertEquals("orphaned tool_result ids", emptySet<String>(), results - uses)
    }

    private fun apply(
        msgs: List<MessageSurgery.Msg>,
        plan: MessageSurgery.DeletePlan,
    ): List<MessageSurgery.Msg> = msgs
        .filterNot { it.id in plan.deleteIds }
        .map { m -> plan.rewrites[m.id]?.let { m.copy(partsJson = it) } ?: m }

    // ── text extraction / rewrite ─────────────────────────────────────────

    @Test
    fun `textOf ignores the attachments inventory`() {
        val json = parts(
            text("real caption"),
            text("<user-attached-files>a.png</user-attached-files>"),
        )
        assertEquals("real caption", MessageSurgery.textOf(json))
    }

    @Test
    fun `rewriteText replaces prose and keeps tool parts`() {
        val json = parts(text("old"), toolUse("t1"))
        val out = MessageSurgery.rewriteText(json, "new")!!
        assertEquals("new", MessageSurgery.textOf(out))
        assertEquals(setOf("t1"), MessageSurgery.toolUseIds(out))
    }

    @Test
    fun `rewriteText keeps the attachments inventory untouched`() {
        val inv = "<user-attached-files>a.png</user-attached-files>"
        val json = parts(text("caption"), text(inv))
        val out = MessageSurgery.rewriteText(json, "edited")!!
        assertTrue(out.contains("user-attached-files"))
        assertEquals("edited", MessageSurgery.textOf(out))
    }

    @Test
    fun `rewriteText collapses multiple text segments into one`() {
        // A streamed turn split across parts must not come back half-edited.
        val json = parts(text("seg one "), text("seg two"), toolUse("t1"))
        val out = MessageSurgery.rewriteText(json, "single")!!
        assertEquals("single", MessageSurgery.textOf(out))
    }

    @Test
    fun `rewriteText refuses a message with no editable text`() {
        assertNull(MessageSurgery.rewriteText(parts(toolUse("t1")), "x"))
    }

    // ── delete: the simple case ───────────────────────────────────────────

    @Test
    fun `deleting a plain text message touches nothing else`() {
        val msgs = listOf(
            msg("m1", "user", parts(text("hello")), 0),
            msg("m2", "assistant", parts(text("hi")), 1),
            msg("m3", "user", parts(text("bye")), 2),
        )
        val plan = MessageSurgery.planDelete(msgs, "m2")
        assertEquals(listOf("m2"), plan.deleteIds)
        assertTrue(plan.rewrites.isEmpty())
        assertWellFormed(apply(msgs, plan))
    }

    @Test
    fun `deleting an unknown id is a no-op`() {
        val msgs = listOf(msg("m1", "user", parts(text("hi")), 0))
        val plan = MessageSurgery.planDelete(msgs, "nope")
        assertTrue(plan.isNoOp)
    }

    // ── delete: tool pairing ──────────────────────────────────────────────

    @Test
    fun `deleting the assistant that made a call strips the orphaned result`() {
        val msgs = listOf(
            msg("m1", "user", parts(text("run it")), 0),
            msg("m2", "assistant", parts(text("running"), toolUse("t1")), 1),
            msg("m3", "user", parts(toolResult("t1")), 2),
            msg("m4", "assistant", parts(text("done")), 3),
        )
        val plan = MessageSurgery.planDelete(msgs, "m2")
        // m3 held ONLY that result → it becomes an empty shell and goes too.
        assertTrue(plan.deleteIds.containsAll(listOf("m2", "m3")))
        val after = apply(msgs, plan)
        assertWellFormed(after)
        assertEquals(listOf("m1", "m4"), after.map { it.id })
        assertTrue(plan.notes.any { it.contains("tool result") })
    }

    @Test
    fun `deleting the result message strips the now-unanswered call`() {
        val msgs = listOf(
            msg("m1", "user", parts(text("run it")), 0),
            msg("m2", "assistant", parts(text("running"), toolUse("t1")), 1),
            msg("m3", "user", parts(toolResult("t1")), 2),
        )
        val plan = MessageSurgery.planDelete(msgs, "m3")
        val after = apply(msgs, plan)
        assertWellFormed(after)
        // m2 survives — it still has prose — but without the dangling call.
        val m2 = after.first { it.id == "m2" }
        assertTrue(MessageSurgery.toolUseIds(m2.partsJson).isEmpty())
        assertEquals("running", MessageSurgery.textOf(m2.partsJson))
        assertTrue(plan.notes.any { it.contains("tool call") })
    }

    @Test
    fun `a results row answering several calls is pruned selectively`() {
        val msgs = listOf(
            msg("m1", "user", parts(text("do both")), 0),
            msg("m2", "assistant", parts(toolUse("t1")), 1),
            msg("m3", "assistant", parts(text("and this"), toolUse("t2")), 2),
            msg("m4", "user", parts(toolResult("t1"), toolResult("t2")), 3),
        )
        val plan = MessageSurgery.planDelete(msgs, "m2")
        val after = apply(msgs, plan)
        assertWellFormed(after)
        val m4 = after.first { it.id == "m4" }
        assertEquals(setOf("t2"), MessageSurgery.toolResultIds(m4.partsJson))
    }

    @Test
    fun `deleting a multi-call assistant strips every matching result`() {
        val msgs = listOf(
            msg("m1", "user", parts(text("go")), 0),
            msg("m2", "assistant", parts(toolUse("t1"), toolUse("t2"), toolUse("t3")), 1),
            msg("m3", "user", parts(toolResult("t1"), toolResult("t2")), 2),
            msg("m4", "user", parts(toolResult("t3")), 3),
        )
        val plan = MessageSurgery.planDelete(msgs, "m2")
        val after = apply(msgs, plan)
        assertWellFormed(after)
        assertEquals(listOf("m1"), after.map { it.id })
    }

    @Test
    fun `a call answered twice keeps its surviving answer`() {
        // Retried tool: the same id answered in two rows. Deleting one answer
        // must NOT strip the call, because the other answer still pairs.
        val msgs = listOf(
            msg("m1", "user", parts(text("go")), 0),
            msg("m2", "assistant", parts(toolUse("t1")), 1),
            msg("m3", "user", parts(toolResult("t1", "first")), 2),
            msg("m4", "user", parts(toolResult("t1", "second")), 3),
        )
        val plan = MessageSurgery.planDelete(msgs, "m3")
        val after = apply(msgs, plan)
        assertWellFormed(after)
        val m2 = after.first { it.id == "m2" }
        assertEquals(setOf("t1"), MessageSurgery.toolUseIds(m2.partsJson))
    }

    @Test
    fun `deleting a mixed message handles both directions at once`() {
        // m3 both answers t1 AND makes t2 — deleting it must strip t1's call
        // (in m2) and t2's result (in m4).
        val msgs = listOf(
            msg("m1", "user", parts(text("go")), 0),
            msg("m2", "assistant", parts(text("call one"), toolUse("t1")), 1),
            msg("m3", "assistant", parts(toolResult("t1"), toolUse("t2")), 2),
            msg("m4", "user", parts(toolResult("t2")), 3),
            msg("m5", "assistant", parts(text("finished")), 4),
        )
        val plan = MessageSurgery.planDelete(msgs, "m3")
        val after = apply(msgs, plan)
        assertWellFormed(after)
        assertTrue(after.map { it.id }.containsAll(listOf("m1", "m2", "m5")))
        assertTrue(MessageSurgery.toolUseIds(after.first { it.id == "m2" }.partsJson).isEmpty())
    }

    @Test
    fun `malformed parts json degrades to no tool ids instead of throwing`() {
        val msgs = listOf(
            msg("m1", "user", "not json at all", 0),
            msg("m2", "assistant", parts(text("hi")), 1),
        )
        val plan = MessageSurgery.planDelete(msgs, "m1")
        assertEquals(listOf("m1"), plan.deleteIds)
        assertWellFormed(apply(msgs, plan))
    }

    @Test
    fun `isEmptyShell distinguishes blank text from real content`() {
        assertTrue(MessageSurgery.isEmptyShell(parts(text("  "))))
        assertTrue(MessageSurgery.isEmptyShell("[]"))
        assertFalse(MessageSurgery.isEmptyShell(parts(text("x"))))
        assertFalse(MessageSurgery.isEmptyShell(parts(toolUse("t1"))))
    }
}
