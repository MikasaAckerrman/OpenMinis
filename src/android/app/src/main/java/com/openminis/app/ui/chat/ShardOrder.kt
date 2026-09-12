package com.openminis.app.ui.chat

/**
 * [T-android-selection-gaps] Document order for MinisTextKit text shards.
 *
 * ## The bug this fixes
 *
 * Copying a long selection dropped chunks: the user selected several paragraphs
 * and pasted only the tail, or a middle block went missing.
 *
 * `SelectionController.crossShardSelectedText` walks shards in document order and
 * slices the two endpoints. The walk is correct only if the ORDER is correct: it
 * skips everything before the first endpoint (`started` stays false) and stops
 * dead at the last one (`break`). Feed it a wrong order and it does not
 * mis-sort — it silently emits a fragment.
 *
 * Order came from `shardOrderKey`, which parsed the trailing integer out of the
 * shard id. That works for markdown fragments (`mdblock:<parent>:<index>#<sub>`)
 * and returns null for the other two id shapes this app actually produces:
 *
 *  - `text:<blockId>#<sub>` — one `AssistantText` block. `blockId` is not
 *    numeric, so the parse failed and every sibling text node in the block was
 *    "unordered".
 *  - `legacy#<sub>` — the whole-message path for pre-migration sessions. No
 *    colon at all, so it never even reached the number parse.
 *
 * With null keys, ordering fell back to comparing `positionInWindow().y` of
 * REGISTERED shards, and a shard that scrolled out of the LazyColumn viewport is
 * not registered — its position resolves to `Float.POSITIVE_INFINITY`. Several
 * recycled shards therefore tie at infinity and land in whatever order the map
 * iterates. That is exactly a long selection: the far end is off-screen. So the
 * failure was systematic, not flaky, and hit precisely the long copies the user
 * reported.
 *
 * The single-shard case never noticed, which is why short copies always worked.
 *
 * ## The fix
 *
 * Order every shard-id shape this app produces, from the id alone — no dependency
 * on whether the row happens to be composed. Both `text:` and `legacy` ids carry
 * a `#subIndex` assigned in first-composition order by
 * [ShardSubIndexAllocator], which IS document order for the sibling text nodes
 * inside one fragment. Using it makes those two shapes orderable while
 * off-screen, the same way `mdblock:` already was.
 *
 * Kept as a separate object with a parsed result rather than an ad-hoc string
 * walk inside `shardOrderKey` so the shapes are stated once and testable
 * without a Compose tree.
 */
internal object ShardOrder {

    /**
     * Parsed pieces of a shard id.
     *
     * @property family the id shape — see [Family].
     * @property blockIndex document index of the fragment within its message, or
     *   null for shapes that have no per-fragment index (single-fragment shapes).
     * @property subIndex index of the sibling text node inside the fragment,
     *   assigned by [ShardSubIndexAllocator] in composition order. 0 when absent.
     */
    data class Parsed(
        val family: Family,
        val blockIndex: Long?,
        val subIndex: Int,
    )

    enum class Family {
        /** `mdblock:<parentBlockId>:<blockIndex>[#<sub>]` — split markdown fragment. */
        MARKDOWN_BLOCK,

        /** `text:<blockId>[#<sub>]` — a single AssistantText block. */
        TEXT_BLOCK,

        /** `legacy[#<sub>]` — whole-message pre-migration content. */
        LEGACY,

        /** Anything else. Ordered by [subIndex] only, if it has one. */
        UNKNOWN,
    }

    fun parse(id: TextShardId): Parsed? = parse(id.shardId)

    fun parse(shardId: String): Parsed? {
        if (shardId.isEmpty()) return null
        // MdText appends `#<subIndex>` to disambiguate sibling Text composables
        // within one fragment. Split it off before looking at the base shape.
        val hashAt = shardId.lastIndexOf('#')
        val base = if (hashAt >= 0) shardId.substring(0, hashAt) else shardId
        val subIndex = if (hashAt >= 0) {
            shardId.substring(hashAt + 1).toIntOrNull() ?: 0
        } else {
            0
        }
        return when {
            base.startsWith("mdblock:") -> {
                // Trailing segment is the fragment index. A non-numeric tail means
                // an id shape we do not know; do not guess an order for it.
                val tail = base.substringAfterLast(':')
                val blockIndex = tail.toLongOrNull() ?: return Parsed(Family.UNKNOWN, null, subIndex)
                Parsed(Family.MARKDOWN_BLOCK, blockIndex, subIndex)
            }
            // blockId is a random identifier, NOT an index — there is exactly one
            // such fragment per block, so subIndex alone orders its text nodes.
            base.startsWith("text:") -> Parsed(Family.TEXT_BLOCK, 0L, subIndex)
            base == "legacy" -> Parsed(Family.LEGACY, 0L, subIndex)
            else -> Parsed(Family.UNKNOWN, null, subIndex)
        }
    }

    /**
     * Whether two shards belong to the same fragment, i.e. their order is fully
     * determined by `subIndex`.
     *
     * Used to decide when a `text:`/`legacy` comparison is meaningful: two ids
     * from DIFFERENT `text:` blocks both report blockIndex 0, so comparing their
     * keys would claim a false order. The caller falls back to visual position in
     * that case, which is the honest answer.
     */
    fun sameFragment(a: TextShardId, b: TextShardId): Boolean {
        if (a.messageId != b.messageId) return false
        return fragmentBase(a) == fragmentBase(b)
    }

    /** Shard id with the `#subIndex` suffix removed. */
    fun fragmentBase(id: TextShardId): String =
        id.shardId.substringBeforeLast('#', missingDelimiterValue = id.shardId)

    /**
     * The fragment's OWNER within its message — the part of the id that is shared
     * by every fragment of one source block.
     *
     * For `mdblock:<parentBlockId>:<blockIndex>` that is `mdblock:<parentBlockId>`,
     * because `blockIndex` restarts at 0 for each parent: comparing indices across
     * two different parents would claim a false order (parent B's fragment 0 is
     * NOT before parent A's fragment 1). For the single-fragment shapes the base
     * is already the owner.
     */
    fun fragmentOwner(id: TextShardId): String {
        val base = fragmentBase(id)
        return if (base.startsWith("mdblock:")) base.substringBeforeLast(':') else base
    }

    /**
     * True when every id shares one message AND one fragment owner, i.e. their
     * relative order is fully determined by (blockIndex, subIndex) and needs no
     * on-screen position.
     *
     * This is the precondition for using [shardOrderKey] to sort. Outside it the
     * caller must fall back to visual position, because nothing in the id says
     * how two different source blocks are stacked in the chat.
     */
    fun comparableByKey(ids: Collection<TextShardId>): Boolean {
        if (ids.size <= 1) return true
        val first = ids.first()
        val message = first.messageId
        val owner = fragmentOwner(first)
        return ids.all { it.messageId == message && fragmentOwner(it) == owner } &&
            ids.all { parse(it)?.blockIndex != null }
    }
}
