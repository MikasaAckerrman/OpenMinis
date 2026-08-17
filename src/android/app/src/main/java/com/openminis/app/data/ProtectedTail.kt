package com.openminis.app.data

/**
 * [T-protected-tail] Single source of truth for "what part of a chat history
 * is too RECENT to compact". The one invariant behind three symptoms the user
 * hit: a summary that swallowed the freshest turns, a model payload that lost
 * them, and a reload that greyed them out.
 *
 * ## The root cause this replaces
 *
 * `compactAll`/`rescue` used to anchor compaction at the LAST history entry
 * and fold the range `[0 .. lastIndex]` — i.e. the entire conversation,
 * newest turns included. The fresh turns were then supposed to be rescued on
 * the READ side (`effectiveAgentHistory` re-injecting the last N user turns).
 * That indirection is fragile: a rescue marker sets `keepN = 0`, a healed
 * anchor can point elsewhere, and any of those paths silently drops exactly
 * the recent turns the user cares about most. Sacrificing the tail up-front
 * and hoping to re-add it later is backwards.
 *
 * ## The invariant
 *
 * The last [protectedUserTurns] user turns — AND everything after and between
 * them (their assistant replies, tool rounds) — are NEVER compacted. The
 * anchor is placed BEFORE that protected region, so the protected tail lives
 * in `postAnchor`, which every downstream path already sends verbatim and
 * never greys. Nothing to re-inject, nothing to lose.
 *
 * Compaction still happens — it just operates on the OLDER part of the
 * history (`[start .. anchor]`), squeezing duplication and prose out of the
 * settled past while the live tail stays word-for-word.
 *
 * Pure logic (no Android, no DB) so the whole decision is unit-tested.
 */
object ProtectedTail {

    /** Default protected tail: the last N user turns stay verbatim forever. */
    const val DEFAULT_PROTECTED_USER_TURNS = 6

    /**
     * One history entry reduced to what the anchor decision needs.
     *
     * @param isUser true for a user-role message (turn boundary).
     * @param hasDbId true when the entry is persisted (has a non-empty
     *        dbMessageId). An anchor MUST be persisted or the compact marker
     *        cannot be restored on reload.
     */
    data class Entry(val isUser: Boolean, val hasDbId: Boolean)

    /**
     * Index of the last entry that MAY be compacted. Everything with a greater
     * index is the protected tail and must be sent/shown verbatim.
     *
     * Rules:
     *  - Walk back from the end counting user turns. The [protectedUserTurns]th
     *    user turn (from the end) opens the protected region; the anchor is the
     *    persisted entry just before it.
     *  - Returns -1 when there is nothing safe to compact: the history is
     *    entirely within the protected tail (fewer than `protectedUserTurns`
     *    user turns exist, or the protected region reaches index 0), or no
     *    persisted anchor exists below the protected region. Callers treat -1
     *    as "skip — too small / all fresh".
     *  - When [protectedUserTurns] <= 0 the protection is disabled and the
     *    anchor is simply the last persisted entry (legacy behaviour), so the
     *    feature can be turned off without a code path fork.
     *
     * @param anchorCeiling optional upper bound on the anchor (inclusive). Used
     *        by a manual "compact before message X" gesture: the protected-tail
     *        anchor is additionally clamped to not exceed the user's chosen
     *        point. Pass null (default) for auto/full compaction.
     */
    fun anchorIndex(
        entries: List<Entry>,
        protectedUserTurns: Int = DEFAULT_PROTECTED_USER_TURNS,
        anchorCeiling: Int? = null,
    ): Int {
        if (entries.isEmpty()) return -1

        fun walkToPersisted(fromIdx: Int): Int {
            var a = fromIdx.coerceAtMost(entries.lastIndex)
            while (a >= 0 && !entries[a].hasDbId) a -= 1
            return a
        }

        // Protection disabled → legacy: last persisted entry (respecting ceiling).
        if (protectedUserTurns <= 0) {
            val start = anchorCeiling?.coerceIn(0, entries.lastIndex) ?: entries.lastIndex
            return walkToPersisted(start)
        }

        // Find where the protected tail begins: the Nth-from-last user turn.
        var userSeen = 0
        var protectStart = -1
        for (i in entries.indices.reversed()) {
            if (entries[i].isUser) {
                userSeen += 1
                if (userSeen == protectedUserTurns) {
                    protectStart = i
                    break
                }
            }
        }

        // Not enough user turns, or protection starts at the very top → the
        // whole history is fresh; nothing older to compact.
        if (protectStart <= 0) return -1

        var anchor = protectStart - 1
        anchorCeiling?.let { anchor = anchor.coerceAtMost(it) }
        return walkToPersisted(anchor)
    }
}
