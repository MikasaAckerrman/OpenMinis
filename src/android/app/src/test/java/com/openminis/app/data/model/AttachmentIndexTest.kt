package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-attachment-numbering] The contract these tests defend: a number spoken by
 * the user ("picture 2") resolves to the same file the model reads from
 * `<user-attached-files>`. Both sides derive from this policy, so a divergence
 * here is a divergence between screen and payload.
 */
class AttachmentIndexTest {

    @Test
    fun `images are numbered first starting at one`() {
        val entries = AttachmentIndex.assign(imageCount = 3, nonImageCount = 0)
        assertEquals(listOf(1, 2, 3), entries.map { it.number })
        assertTrue(entries.all { it.isImage })
        assertEquals(listOf(0, 1, 2), entries.map { it.indexWithinKind })
    }

    @Test
    fun `files continue the image sequence rather than restarting`() {
        // The whole point: one sequence per turn. Restarting at 1 for files
        // would give a bubble two tiles both labelled "1".
        val entries = AttachmentIndex.assign(imageCount = 2, nonImageCount = 2)
        assertEquals(listOf(1, 2, 3, 4), entries.map { it.number })
        assertEquals(listOf(true, true, false, false), entries.map { it.isImage })
        // indexWithinKind restarts per kind — it indexes the caller's own list.
        assertEquals(listOf(0, 1, 0, 1), entries.map { it.indexWithinKind })
    }

    @Test
    fun `numbers are unique across the whole turn`() {
        val entries = AttachmentIndex.assign(imageCount = 4, nonImageCount = 5)
        assertEquals(9, entries.size)
        assertEquals(9, entries.map { it.number }.toSet().size)
    }

    @Test
    fun `helper offsets agree with assign`() {
        // The composable uses imageNumber/fileNumber instead of assign (it
        // already has the split), so the two paths must not drift.
        val imageCount = 3
        val fileCount = 2
        val fromAssign = AttachmentIndex.assign(imageCount, fileCount).map { it.number }
        val fromHelpers =
            (0 until imageCount).map { AttachmentIndex.imageNumber(it) } +
                (0 until fileCount).map { AttachmentIndex.fileNumber(it, imageCount) }
        assertEquals(fromAssign, fromHelpers)
    }

    @Test
    fun `file numbering with zero images starts at one`() {
        assertEquals(1, AttachmentIndex.fileNumber(0, imageCount = 0))
        assertEquals(2, AttachmentIndex.fileNumber(1, imageCount = 0))
    }

    @Test
    fun `single attachment is not numbered`() {
        // A "1" badge on a lone tile is noise: "the picture" is unambiguous.
        assertFalse(AttachmentIndex.shouldNumber(1, 0))
        assertFalse(AttachmentIndex.shouldNumber(0, 1))
        assertFalse(AttachmentIndex.shouldNumber(0, 0))
    }

    @Test
    fun `two or more attachments are numbered including mixed kinds`() {
        assertTrue(AttachmentIndex.shouldNumber(2, 0))
        assertTrue(AttachmentIndex.shouldNumber(0, 2))
        // One image + one file still needs numbers: "the picture" is fine but
        // "the file" vs "the picture" gets ambiguous the moment there are two
        // tiles and the user refers to position.
        assertTrue(AttachmentIndex.shouldNumber(1, 1))
    }

    @Test
    fun `negative counts are clamped instead of throwing`() {
        // This runs on the send path; a numbering helper must never be the
        // reason a message fails to send.
        assertEquals(emptyList<AttachmentIndex.Entry>(), AttachmentIndex.assign(-3, -1))
        assertEquals(1, AttachmentIndex.fileNumber(0, imageCount = -5))
        assertFalse(AttachmentIndex.shouldNumber(-2, -2))
    }

    @Test
    fun `mixed pick order does not change display numbering`() {
        // The user picked [doc, photo, zip]; the bubble renders [photo, doc, zip]
        // and the XML must be emitted in that same order. This test pins the
        // ordering rule the XML builder relies on (images first, then files, each
        // group keeping pick order).
        val pickOrder = listOf(false, true, false) // doc, photo, zip
        val expectedDisplay = pickOrder.withIndex()
            .filter { it.value }
            .map { it.index } +
            pickOrder.withIndex()
                .filterNot { it.value }
                .map { it.index }
        assertEquals(listOf(1, 0, 2), expectedDisplay)

        val entries = AttachmentIndex.assign(imageCount = 1, nonImageCount = 2)
        assertEquals(listOf(1, 2, 3), entries.map { it.number })
        assertEquals(listOf(true, false, false), entries.map { it.isImage })
    }
}
