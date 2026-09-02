package com.openminis.app.data.model

/**
 * [T-attachment-numbering] Display numbers for the attachments of a single user
 * turn — the numbers the user sees on the tiles and the numbers the model reads
 * in the `<user-attached-files>` inventory.
 *
 * Why this is a separate policy instead of two `forEachIndexed` calls:
 *
 * The UI and the LLM inventory build their lists on different paths and in
 * DIFFERENT ORDERS. `UserAttachmentList` renders `imageUris` first and the
 * non-image names after (`attachmentNames = imageNames + nonImageNames`), while
 * the upload loop in `prepareAttachments` walks the user's raw `attachments` in
 * pick order, appending image and non-image metadata as it encounters them. For
 * a pick of `[doc, photo, zip]` the tile order is `[photo, doc, zip]` and the
 * naive metadata order is `[doc, photo, zip]`.
 *
 * If each side numbered its own list, "picture 2" would mean the photo on
 * screen and the doc in the payload — the exact ambiguity numbering is supposed
 * to remove. So both sides derive their labels from [assign] over the same
 * images-first sequence.
 *
 * Numbers are 1-based because they exist to be spoken ("look at picture 2"),
 * and they are assigned across the whole turn — images and files share one
 * sequence, so no two tiles in a bubble ever carry the same number.
 */
object AttachmentIndex {

    /** Ordinal + the kind of tile it belongs to, in display order. */
    data class Entry(
        /** 1-based number shown on the tile and written into the XML. */
        val number: Int,
        /** True when this entry is one of the images (rendered first). */
        val isImage: Boolean,
        /**
         * Index into the caller's own image or non-image list — lets the caller
         * pair the number back with its parallel arrays without re-deriving the
         * split.
         */
         val indexWithinKind: Int,
    )

    /**
     * Assign display numbers for [imageCount] images followed by
     * [nonImageCount] files.
     *
     * Negative inputs are treated as zero rather than throwing: this runs on
     * the send path, and a numbering helper must never be the reason a user's
     * message fails to send.
     */
    fun assign(imageCount: Int, nonImageCount: Int): List<Entry> {
        val images = imageCount.coerceAtLeast(0)
        val files = nonImageCount.coerceAtLeast(0)
        val out = ArrayList<Entry>(images + files)
        var n = 1
        for (i in 0 until images) {
            out.add(Entry(number = n, isImage = true, indexWithinKind = i))
            n++
        }
        for (i in 0 until files) {
            out.add(Entry(number = n, isImage = false, indexWithinKind = i))
            n++
        }
        return out
    }

    /**
     * Numbers for a list still in PICK order, aligned with the input.
     *
     * The composer holds attachments in the order the user picked them, but the
     * bubble and the XML inventory both use images-first order. Numbering the
     * composer chips by their pick position would print a different number than
     * the one the model ends up reading for the same file — so the chip would
     * say "2" and "picture 2" would mean something else. This maps each
     * position to its number in the images-first sequence.
     *
     * [isImage] is a predicate rather than a split list so the caller does not
     * have to build (and keep in sync) a second pair of lists just to ask.
     */
    fun <T> numberInPickOrder(items: List<T>, isImage: (T) -> Boolean): List<Int> {
        val imageTotal = items.count(isImage)
        var nextImage = 1
        var nextFile = imageTotal + 1
        return items.map { item ->
            if (isImage(item)) nextImage++ else nextFile++
        }
    }

    /**
     * The number to show on a tile, given the UI's own split.
     *
     * [UserAttachmentList] knows `imageUris` and the dropped-prefix file names;
     * this keeps the +1 offset arithmetic in one place instead of inline in the
     * composable, where an off-by-one is invisible on review.
     */
    fun imageNumber(indexWithinImages: Int): Int = indexWithinImages + 1

    /** Same for a non-image tile: files continue the image sequence. */
    fun fileNumber(indexWithinFiles: Int, imageCount: Int): Int =
        imageCount.coerceAtLeast(0) + indexWithinFiles + 1

    /**
     * Total tiles a bubble will show. Used only to decide whether numbering is
     * worth rendering at all: a single attachment needs no number — "the
     * picture" is unambiguous, and a "1" badge is pure noise.
     */
    fun shouldNumber(imageCount: Int, nonImageCount: Int): Boolean =
        imageCount.coerceAtLeast(0) + nonImageCount.coerceAtLeast(0) >= 2
}
