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
     * [T-compact-tail-vs-window] Protected tail sized by the ACTIVE model's
     * context window instead of the fixed [DEFAULT_PROTECTED_USER_TURNS].
     *
     * ## The failure this fixes
     *
     * User report: switch from a large-context model to a small-context one
     * (or run /compact on the small one) → "compacted but the request still
     * doesn't fit". Root cause: the protected tail was ALWAYS 6 user turns
     * regardless of window. On a 128k+ window 6 verbatim agent turns (with
     * their tool rounds) are noise; on a 32k window those same 6 turns can
     * outweigh the entire budget — compaction squeezed only the OLD part,
     * and the mathematically-uncompactable protected tail kept the session
     * over the limit. Compaction must be sized RELATIVE to the window it
     * is compacting FOR (user's own diagnosis, confirmed in code).
     *
     * Ladder (conservative — one turn per window tier down, floor of 2):
     *   ≥128k → 6   64k–128k → 5   32k–64k → 4   16k–32k → 3   <16k → 2
     *
     * Read side uses the same function, so the tail a session SENDS tracks
     * the model it is ON, not the model it was compacted on: reopening a
     * compacted session on a smaller window trims the sent tail; on a larger
     * one it simply finds fewer turns than asked and sends what exists.
     *
     * Pure logic — unit-tested.
     */
    fun protectedTurnsForWindow(contextWindow: Int): Int = when {
        contextWindow >= 128_000 -> 6
        contextWindow >= 64_000 -> 5
        contextWindow >= 32_000 -> 4
        contextWindow >= 16_000 -> 3
        else -> 2
    }

    /**
     * [T-tail-token-budget] VERBATIM tail budget in TOKENS, proportional to
     * the active model's window. This is the second half of "size the tail
     * to the window": [protectedTurnsForWindow] bounds the tail by TURN COUNT,
     * but a turn is not a fixed size — one tool-heavy agent turn can carry
     * dozens of kilobytes of tool_result parts, and six of those outweigh a
     * 32k window while six one-liners fit in a rounding error. Counting turns
     * alone cannot see that; the tail also needs a byte/token ceiling.
     *
     * Formula: window / 4, clamped to [1024, 32768].
     *   8k → 2048   16k → 4096   32k → 8192   131k+ → 32768 (cap)
     * The other ~3/4 of the window stays for the system prompt, tools
     * schema, the compact summary itself, and the model's output — a tail
     * that eats half the window starves exactly those. Both limits apply
     * TOGETHER: the tail is `min(count-limit, budget-limit)` turns, so a
     * small window gets a short tail even when turns are fat, and a large
     * window never gets more than 6 turns however cheap they are.
     *
     * Pure logic — unit-tested.
     */
    fun tailBudgetTokens(contextWindow: Int): Int =
        (contextWindow / 4).coerceIn(TAIL_BUDGET_MIN_TOKENS, TAIL_BUDGET_MAX_TOKENS)
    const val TAIL_BUDGET_MIN_TOKENS = 1_024
    const val TAIL_BUDGET_MAX_TOKENS = 32_768

    /**
     * One history entry reduced to what the anchor decision needs.
     *
     * @param isUser true for a user-role message (turn boundary).
     * @param hasDbId true when the entry is persisted (has a non-empty
     *        dbMessageId). An anchor MUST be persisted or the compact marker
     *        cannot be restored on reload.
     * @param tokens approximate payload cost of this entry (text + parts).
     *        Used by the token-budgeted anchor path; 0 by default so
     *        count-only callers keep the legacy behaviour.
     */
    data class Entry(val isUser: Boolean, val hasDbId: Boolean, val tokens: Int = 0)

    /**
     * Index of the last entry that MAY be compacted. Everything with a greater
     * index is the protected tail and must be sent/shown verbatim.
     *
     * Rules:
     *  - Walk back from the end counting user turns. The [protectedUserTurns]th
     *    user turn (from the end) opens the protected region; the anchor is the
     *    persisted entry just before it.
     *  - [tokenBudget] [T-tail-token-budget]: the walk ALSO stops when the
     *    verbatim tail has already consumed the budget and at least one full
     *    user turn is protected — a fat tool-heavy tail must not outweigh the
     *    model's window (see [tailBudgetTokens]). The FIRST user turn is
     *    always included even if it alone exceeds the budget: a tail of zero
     *    turns would blind the model to everything after the summary, and a
     *    single oversized turn is handled downstream (preAnchor/postAnchor
     *    tool_result prune, image budget, offload). Pass Int.MAX_VALUE (the
     *    default) to disable the token limit and keep count-only behaviour.
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
        tokenBudget: Int = Int.MAX_VALUE,
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

        // Find where the protected tail begins: the Nth-from-last user turn,
        // with the token budget as a second, independent stop condition.
        // Traced semantics: the count stop fires AFTER accepting the Nth user
        // (that turn opens the tail), while the budget stop REFUSES the next
        // turn and keeps the tail at the first turn accepted so far.
        var userSeen = 0
        var tailTokens = 0
        var firstAcceptedUserIdx = -1
        var protectStart = -1
        for (i in entries.indices.reversed()) {
            if (entries[i].isUser) {
                if (firstAcceptedUserIdx != -1 && tokenBudget != Int.MAX_VALUE &&
                    tailTokens + entries[i].tokens > tokenBudget
                ) {
                    // Budget gate: accepting THIS turn's messages on top of
                    // what is already protected would exceed the budget. The
                    // already-accepted turns stay protected (the first one is
                    // accepted unconditionally — a tail of zero turns would
                    // blind the model to everything after the summary).
                    protectStart = firstAcceptedUserIdx
                    break
                }
                userSeen += 1
                if (firstAcceptedUserIdx == -1) firstAcceptedUserIdx = i
                if (userSeen == protectedUserTurns) {
                    protectStart = i
                    break
                }
            }
            tailTokens += entries[i].tokens
        }

        // Not enough user turns, or protection starts at the very top → the
        // whole history is fresh; nothing older to compact.
        if (protectStart <= 0) return -1

        var anchor = protectStart - 1
        anchorCeiling?.let { anchor = anchor.coerceAtMost(it) }
        return walkToPersisted(anchor)
    }
}
