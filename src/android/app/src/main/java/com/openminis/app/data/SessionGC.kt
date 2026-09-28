package com.openminis.app.data

import org.json.JSONArray

/**
 * [T-session-gc] Safe per-session garbage collection — the agent-side answer
 * to "how much does the session weigh and what can be cleaned without
 * losing anything". HARD RULES (user 29.09):
 *
 *  1. NEVER delete a message row. The only rewrite is fat OLD tool_result
 *     bodies → offloaded verbatim to the session's offloads dir
 *     ([ContextOffload]) with a `[CONTEXT OFFLOADED]` stub in their place.
 *     The bytes survive on disk, file_read reaches them, the wire payload
 *     and the DB row shrink. Lossless by construction.
 *  2. The protected tail is untouchable: the last N user turns and
 *     everything after them ([ProtectedTail] — the same boundary the
 *     compactor honors, reused so the two systems can never disagree).
 *  3. Compact markers, rescue digests and FAILED tool_results are never
 *     rewritten: errors are small and are exactly the context a future
 *     turn needs; markers are load-bearing structure.
 *  4. Already-offloaded parts (`[CONTEXT OFFLOADED]` prefix) are skipped —
 *     idempotent by construction.
 *
 * Parts shape follows persistToolResultMessage verbatim: tool results are
 * persisted as role="user" rows of the form
 *   [{"type":"toolResult","value":{"toolUseId":…,"name":…,"output":…,
 *     "success":bool,"snapshot":{"type":"text","text":…}}}]
 * — `output` is the full body, `snapshot` its last-30-lines digest.
 * Selection is pure (unit-tested); the executor (ChatViewModel's
 * executeTool branch) performs offload+rewrite through the
 * MutationJournal-disciplined DAO update path.
 */
object SessionGC {

    /** Same threshold as ToolResultMicrocompact: below this a rewrite buys nothing. */
    const val MIN_GARBAGE_CHARS = 1_200

    /** Protected tail for GC: same count the compactor uses at ≥128k windows. */
    const val PROTECTED_USER_TURNS = 6

    data class Row(
        val id: String,
        val role: String,
        val sortOrder: Long,
        val partsJson: String?,
    )

    data class Candidate(
        val messageId: String,
        val toolResultId: String,
        val toolName: String,
        val chars: Int,
    )

    /**
     * Index of the last row that MAY be rewritten; everything after is the
     * protected tail. NOTE: ProtectedTail counts USER turns as boundaries —
     * tool-result rows are role "user" too (persistToolResultMessage), so
     * they count as part of a user turn's span, which is exactly the
     * compactor's view of the tail. -1 = protect everything.
     */
    fun gcBoundary(rows: List<Row>): Int {
        val entries = rows.map { r ->
            ProtectedTail.Entry(isUser = r.role == "user", hasDbId = true, tokens = (r.partsJson?.length ?: 0) / 4)
        }
        return ProtectedTail.anchorIndex(entries, protectedUserTurns = PROTECTED_USER_TURNS)
    }

    /**
     * Fat, old, successful, not-yet-offloaded tool_result bodies below the
     * boundary. Unknown/legacy shapes are skipped, never guessed: a GC that
     * misparses and rewrites the wrong bytes is worse than no GC.
     */
    fun selectCandidates(rows: List<Row>): List<Candidate> {
        val boundary = gcBoundary(rows)
        if (boundary < 0) return emptyList()
        val out = mutableListOf<Candidate>()
        for ((index, r) in rows.withIndex()) {
            if (index > boundary) break
            val pj = r.partsJson ?: continue
            val arr = runCatching { JSONArray(pj) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                if (part.optString("type") != "toolResult") continue
                val value = part.optJSONObject("value") ?: continue
                if (!value.optBoolean("success", true)) continue // failed results: never rewrite
                val content = value.optString("output", "")
                if (content.length < MIN_GARBAGE_CHARS) continue
                if (content.startsWith(ContextOffload.OFFLOADED_PREFIX)) continue
                out += Candidate(
                    messageId = r.id,
                    toolResultId = value.optString("toolUseId", "unknown-$index-$i"),
                    toolName = value.optString("name", "tool"),
                    chars = content.length,
                )
            }
        }
        return out
    }

    /** Weight report the agent sees: per-role row/char totals. Pure. */
    data class Weight(
        val rows: Int,
        val totalChars: Int,
        val userTextChars: Int,
        val assistantChars: Int,
        val toolResultChars: Int,
        val offloadableChars: Int,
    )

    fun weigh(rows: List<Row>, candidates: List<Candidate>): Weight {
        var total = 0
        var assistant = 0
        var tool = 0
        for (r in rows) {
            val pj = r.partsJson ?: continue
            total += pj.length
            if (r.role == "assistant") assistant += pj.length
            val arr = runCatching { JSONArray(pj) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                if (part.optString("type") == "toolResult") {
                    tool += part.optJSONObject("value")?.optString("output", "")?.length ?: 0
                }
            }
        }
        val userText = (total - assistant - tool).coerceAtLeast(0)
        return Weight(
            rows = rows.size,
            totalChars = total,
            userTextChars = userText,
            assistantChars = assistant,
            toolResultChars = tool,
            offloadableChars = candidates.sumOf { it.chars },
        )
    }

    /**
     * Rewrite one row's partsJson with an offloaded stub replacing the
     * candidate's `output`. Re-checks every invariant at write time (id
     * present, still fat, still successful, not already offloaded) — the
     * executor must not write a rewrite it cannot verify. The snapshot
     * digest (last 30 lines) is left intact: it is bounded by design.
     * Returns the new partsJson, or null when the part vanished or drifted
     * since selection.
     */
    fun rewritePart(partsJson: String, toolResultId: String, stub: String): String? {
        val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return null
        var replaced = false
        for (i in 0 until arr.length()) {
            val part = arr.optJSONObject(i) ?: continue
            if (part.optString("type") != "toolResult") continue
            val value = part.optJSONObject("value") ?: continue
            if (value.optString("toolUseId") != toolResultId) continue
            if (!value.optBoolean("success", true)) return null
            val content = value.optString("output", "")
            if (content.length < MIN_GARBAGE_CHARS) return null
            if (content.startsWith(ContextOffload.OFFLOADED_PREFIX)) return null
            value.put("output", stub)
            replaced = true
        }
        if (!replaced) return null
        return arr.toString()
    }
}
