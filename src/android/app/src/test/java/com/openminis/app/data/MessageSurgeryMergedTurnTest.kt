package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-delete-merged-turn] [T-delete-attachment-bytes] Deletion and rewrite of a
 * turn that spans SEVERAL DB rows, plus the attachment-path extraction the
 * delete path uses to remove the actual files.
 *
 * The failures these cover are the ones the user reported:
 *  - an assistant bubble is the merge of every consecutive assistant row (one
 *    per tool round), so a delete planned against ONE id left the rest of the
 *    turn in the database and it came back on reload;
 *  - a deleted photo's bytes stayed on disk forever, because nothing walked the
 *    mediaRef parts of the rows being removed.
 */
class MessageSurgeryMergedTurnTest {

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun text(v: String) = """{"type":"text","value":${q(v)}}"""
    private fun media(rel: String, linux: String? = null): String {
        val lp = if (linux != null) ""","linuxPath":${q(linux)}""" else ""
        return """{"type":"mediaRef","value":{"id":"i","relativePath":${q(rel)},"mimeType":"image/jpeg"$lp}}"""
    }
    private fun use(id: String) =
        """{"type":"toolUse","value":{"toolUseId":${q(id)},"name":"shell","input":"{}"}}"""
    private fun res(id: String) =
        """{"type":"toolResult","value":{"toolUseId":${q(id)},"output":"ok","success":true}}"""
    private fun parts(vararg p: String) = "[" + p.joinToString(",") + "]"
    private fun msg(id: String, role: String, partsJson: String, order: Int) =
        MessageSurgery.Msg(id, role, partsJson, order)

    // ── mediaPaths ────────────────────────────────────────────────────────

    @Test
    fun mediaPaths_extractsBothLocations() {
        val m = MessageSurgery.mediaPaths(
            parts(text("hi"), media("2026/08/14/s/a.jpg", "/var/minis/attachments/uploads/a.jpg")),
        )
        assertEquals(listOf("2026/08/14/s/a.jpg"), m.relative)
        assertEquals(listOf("/var/minis/attachments/uploads/a.jpg"), m.linux)
    }

    @Test
    fun mediaPaths_legacyRowWithoutLinuxPath() {
        // Rows written before linuxPath was persisted must not invent one.
        val m = MessageSurgery.mediaPaths(parts(media("x/y.png")))
        assertEquals(1, m.relative.size)
        assertTrue(m.linux.isEmpty())
    }

    @Test
    fun mediaPaths_textOnlyAndMalformedAreEmpty() {
        assertTrue(MessageSurgery.mediaPaths(parts(text("no media"))).isEmpty)
        // Never throws on a corrupt row — the delete path calls this inline.
        assertTrue(MessageSurgery.mediaPaths("not json at all").isEmpty)
    }

    // ── removeTextParts ───────────────────────────────────────────────────

    @Test
    fun removeTextParts_keepsToolsMediaAndInventory() {
        // No raw newline in the fixture: a literal newline inside a JSON string
        // is invalid, and parts() would silently yield an empty array — the test
        // would then "pass" against nothing.
        val inventory = text("<user-attached-files><file path=\"/x\"/></user-attached-files>")
        val out = MessageSurgery.removeTextParts(
            parts(text("prose"), use("t1"), media("a/b.jpg"), inventory),
        )
        assertEquals("", MessageSurgery.textOf(out))
        assertEquals(setOf("t1"), MessageSurgery.toolUseIds(out))
        assertEquals(listOf("a/b.jpg"), MessageSurgery.mediaPaths(out).relative)
        assertTrue("attachments inventory must survive", out.contains("user-attached-files"))
    }

    // ── planDelete, multi-target ──────────────────────────────────────────

    @Test
    fun deletingAMergedTurnRemovesEveryRowAndRewritesNothing() {
        val msgs = listOf(
            msg("u1", "user", parts(text("do it")), 0),
            msg("a1", "assistant", parts(use("t1")), 1),
            msg("a2", "assistant", parts(res("t1"), text("done")), 2),
        )
        val plan = MessageSurgery.planDelete(msgs, listOf("a1", "a2"))

        assertEquals(setOf("a1", "a2"), plan.deleteIds.toSet())
        // Both sides of the pairing are leaving, so no survivor needs surgery.
        assertTrue(plan.rewrites.isEmpty())
        assertTrue("u1" !in plan.deleteIds)
        // The "became empty" note would be a lie here — nothing extra was taken.
        assertTrue(plan.notes.none { it.contains("became empty") })
    }

    @Test
    fun multiTargetStillRewritesASurvivingAnswer() {
        val msgs = listOf(
            msg("a1", "assistant", parts(use("t1")), 0),
            msg("a2", "assistant", parts(text("mid")), 1),
            msg("a3", "assistant", parts(res("t1"), text("keep me")), 2),
        )
        val plan = MessageSurgery.planDelete(msgs, listOf("a1", "a2"))

        assertEquals(setOf("a1", "a2"), plan.deleteIds.toSet())
        assertEquals(setOf("a3"), plan.rewrites.keys)
        val rewritten = plan.rewrites.getValue("a3")
        assertTrue(MessageSurgery.toolResultIds(rewritten).isEmpty())
        assertEquals("keep me", MessageSurgery.textOf(rewritten))
    }

    @Test
    fun singleTargetOverloadStillWorks() {
        val msgs = listOf(
            msg("a1", "assistant", parts(use("t1")), 0),
            msg("a2", "assistant", parts(res("t1"), text("done")), 1),
        )
        val plan = MessageSurgery.planDelete(msgs, "a1")
        assertEquals(listOf("a1"), plan.deleteIds)
        assertNotNull(plan.rewrites["a2"])
        assertEquals("done", MessageSurgery.textOf(plan.rewrites.getValue("a2")))
    }

    @Test
    fun rowLeftEmptyByStrippingIsDeletedToo() {
        val msgs = listOf(
            msg("a1", "assistant", parts(use("t1")), 0),
            msg("a2", "assistant", parts(res("t1")), 1),
        )
        val plan = MessageSurgery.planDelete(msgs, "a1")
        assertEquals(setOf("a1", "a2"), plan.deleteIds.toSet())
        assertTrue(plan.notes.any { it.contains("became empty") })
    }

    @Test
    fun unknownTargetIsANoOp() {
        assertTrue(MessageSurgery.planDelete(emptyList(), listOf("nope")).isNoOp)
    }

    @Test
    fun rewriteTextPreservesEverythingButProse() {
        val out = MessageSurgery.rewriteText(
            parts(text("old"), use("t1"), media("a/b.jpg")),
            "new",
        )
        assertNotNull(out)
        assertEquals("new", MessageSurgery.textOf(out!!))
        assertEquals(setOf("t1"), MessageSurgery.toolUseIds(out))
        assertEquals(listOf("a/b.jpg"), MessageSurgery.mediaPaths(out).relative)
    }

    @Test
    fun rewriteRefusesWhenThereIsNoProse() {
        assertNull(MessageSurgery.rewriteText(parts(use("t1")), "x"))
    }
}
