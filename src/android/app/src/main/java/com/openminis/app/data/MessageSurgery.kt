package com.openminis.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-message-surgery] Pure logic for editing and deleting individual messages
 * in a session, operating directly on the persisted `parts_json` payloads.
 *
 * Why this is not just "delete the row": a chat history sent to a provider is
 * not a flat list of texts, it is a protocol. Every `tool_use` an assistant
 * emitted MUST be answered by a `tool_result` with the same id in a later
 * message, or the request is rejected outright ("unexpected tool_use_id" /
 * "tool_use without tool_result"). Deleting the message that carried the
 * results, or the one that carried the calls, silently produces a session that
 * cannot talk to the model at all — the exact class of breakage the user is
 * already recovering from. So deletion is planned, not performed blindly: this
 * object works out which additional parts must be dropped or stubbed to keep
 * the history well-formed, and the caller applies that plan.
 *
 * Kept free of Android and Room types so the whole pairing matrix is
 * unit-testable on the JVM.
 */
object MessageSurgery {

    /** One message as far as surgery is concerned. */
    data class Msg(
        val id: String,
        val role: String,
        val partsJson: String,
        val sortOrder: Int,
    )

    /**
     * What must happen for a deletion to leave the history well-formed.
     *
     * @param deleteIds rows to remove entirely.
     * @param rewrites  rows whose parts_json must be replaced (id → new json),
     *                  because they referenced a tool call that no longer
     *                  exists on the other side.
     * @param notes     human-readable explanation of every non-obvious action,
     *                  surfaced to the user so a "delete one message" that
     *                  touched three rows is never silent.
     */
    data class DeletePlan(
        val deleteIds: List<String>,
        val rewrites: Map<String, String>,
        val notes: List<String>,
    ) {
        val isNoOp: Boolean get() = deleteIds.isEmpty() && rewrites.isEmpty()
    }

    // ── parts_json helpers ────────────────────────────────────────────────

    private fun parts(json: String): JSONArray =
        try { JSONArray(json) } catch (_: Exception) { JSONArray() }

    /** tool_use ids emitted by a message. */
    fun toolUseIds(partsJson: String): Set<String> {
        val out = LinkedHashSet<String>()
        val arr = parts(partsJson)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "toolUse") {
                o.optJSONObject("value")?.optString("toolUseId")?.takeIf { it.isNotEmpty() }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    /** tool_result ids answered by a message. */
    fun toolResultIds(partsJson: String): Set<String> {
        val out = LinkedHashSet<String>()
        val arr = parts(partsJson)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "toolResult") {
                o.optJSONObject("value")?.optString("toolUseId")?.takeIf { it.isNotEmpty() }
                    ?.let { out.add(it) }
            }
        }
        return out
    }

    /** Concatenated text of a message, excluding the attachments inventory. */
    fun textOf(partsJson: String): String {
        val sb = StringBuilder()
        val arr = parts(partsJson)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "text") continue
            val v = o.optString("value", "")
            if (v.contains("<user-attached-files>")) continue
            sb.append(v)
        }
        return sb.toString()
    }

    /**
     * Replace the message's editable text with [newText], preserving
     * everything else (tool calls, results, media refs, the attachments
     * inventory) exactly as-is.
     *
     * The first text part becomes [newText]; any further plain-text parts are
     * dropped, because a message whose text was split across several parts
     * (streamed in segments, interrupted by a tool call) must not come back
     * with the edit applied to one segment and stale prose in the others.
     * Returns null when there is nothing to edit — the caller should then
     * refuse rather than inventing a part and changing the message's shape.
     */
    fun rewriteText(partsJson: String, newText: String): String? {
        val arr = parts(partsJson)
        val out = JSONArray()
        var wrote = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isPlainText = o.optString("type") == "text" &&
                !o.optString("value", "").contains("<user-attached-files>")
            if (!isPlainText) {
                out.put(o)
                continue
            }
            if (!wrote) {
                out.put(JSONObject().put("type", "text").put("value", newText))
                wrote = true
            }
            // Subsequent plain-text parts are intentionally dropped.
        }
        if (!wrote) return null
        return out.toString()
    }

    /**
     * Remove every `toolResult` part whose id is in [ids]. Used when the
     * assistant message that made those calls is being deleted — an
     * unanswerable result is as invalid as an unanswered call.
     */
    fun stripToolResults(partsJson: String, ids: Set<String>): String {
        val arr = parts(partsJson)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isTarget = o.optString("type") == "toolResult" &&
                (o.optJSONObject("value")?.optString("toolUseId") ?: "") in ids
            if (!isTarget) out.put(o)
        }
        return out.toString()
    }

    /**
     * Remove every `toolUse` part whose id is in [ids] — the mirror case:
     * the message holding their results is being deleted, so the calls can no
     * longer be answered.
     */
    fun stripToolUses(partsJson: String, ids: Set<String>): String {
        val arr = parts(partsJson)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isTarget = o.optString("type") == "toolUse" &&
                (o.optJSONObject("value")?.optString("toolUseId") ?: "") in ids
            if (!isTarget) out.put(o)
        }
        return out.toString()
    }

    /** True when a message would carry no meaningful content after a rewrite. */
    fun isEmptyShell(partsJson: String): Boolean {
        val arr = parts(partsJson)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("type")) {
                "text" -> if (o.optString("value", "").isNotBlank()) return false
                else -> return false
            }
        }
        return true
    }

    // ── the plan ──────────────────────────────────────────────────────────

    /**
     * Work out how to delete [targetId] without breaking tool pairing.
     *
     * Rules, in order:
     *   - the target row is deleted;
     *   - any `tool_result` elsewhere answering a call the target made is
     *     stripped (its call is going away);
     *   - any `tool_use` elsewhere whose only answer lived in the target is
     *     stripped (it would otherwise go unanswered);
     *   - a row left with nothing but empty text after stripping is deleted
     *     too, rather than sent as a contentless turn.
     *
     * Deliberately NOT done here: re-pointing a result at a different call,
     * or fabricating a placeholder result. Both hide the user's edit from
     * themselves; the agent loop's own sanitizer already injects placeholders
     * for genuinely interrupted calls, and that is a different situation from
     * "the user removed this turn on purpose".
     */
    fun planDelete(messages: List<Msg>, targetId: String): DeletePlan {
        val target = messages.firstOrNull { it.id == targetId }
            ?: return DeletePlan(emptyList(), emptyMap(), listOf("message not found"))

        val deleteIds = mutableListOf(targetId)
        val rewrites = LinkedHashMap<String, String>()
        val notes = mutableListOf<String>()

        val targetUses = toolUseIds(target.partsJson)
        val targetResults = toolResultIds(target.partsJson)

        // Case 1: target made tool calls → strip their results downstream.
        if (targetUses.isNotEmpty()) {
            var strippedResults = 0
            for (m in messages) {
                if (m.id == targetId) continue
                val hits = toolResultIds(m.partsJson).intersect(targetUses)
                if (hits.isEmpty()) continue
                val rewritten = stripToolResults(m.partsJson, hits)
                strippedResults += hits.size
                if (isEmptyShell(rewritten)) {
                    deleteIds.add(m.id)
                } else {
                    rewrites[m.id] = rewritten
                }
            }
            if (strippedResults > 0) {
                notes.add(
                    "removed $strippedResults tool result(s) that answered calls made by the deleted message",
                )
            }
        }

        // Case 2: target answered tool calls → those calls lose their answer.
        // Strip a call only if NO surviving message answers it (a retried tool
        // can legitimately have its result in more than one place).
        if (targetResults.isNotEmpty()) {
            val answeredElsewhere = LinkedHashSet<String>()
            for (m in messages) {
                if (m.id == targetId || m.id in deleteIds) continue
                answeredElsewhere.addAll(toolResultIds(m.partsJson))
            }
            val orphanedCalls = targetResults - answeredElsewhere
            if (orphanedCalls.isNotEmpty()) {
                var strippedUses = 0
                for (m in messages) {
                    if (m.id == targetId || m.id in deleteIds) continue
                    val hits = toolUseIds(m.partsJson).intersect(orphanedCalls)
                    if (hits.isEmpty()) continue
                    // A row may already be scheduled for rewrite by case 1.
                    val base = rewrites[m.id] ?: m.partsJson
                    val rewritten = stripToolUses(base, hits)
                    strippedUses += hits.size
                    if (isEmptyShell(rewritten)) {
                        deleteIds.add(m.id)
                        rewrites.remove(m.id)
                    } else {
                        rewrites[m.id] = rewritten
                    }
                }
                if (strippedUses > 0) {
                    notes.add(
                        "removed $strippedUses tool call(s) whose results were in the deleted message",
                    )
                }
            }
        }

        val extraDeletes = deleteIds.size - 1
        if (extraDeletes > 0) {
            notes.add("$extraDeletes further message(s) became empty and were removed too")
        }
        return DeletePlan(deleteIds.distinct(), rewrites, notes)
    }
}
