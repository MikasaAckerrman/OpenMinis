package com.openminis.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [session-longpress-compress] Turn a session's persisted `messages` rows into
 * a dense [RescueDigest], WITHOUT any model call.
 *
 * ## Why LLM-free
 *
 * The trigger for this feature is "the session lags, I open it and it crashes":
 * that is a too-large-context session. Asking a model to summarise it would
 * need the very round-trip that is failing — and the user explicitly asked for
 * a compressor that "doesn't make the model choke" and works "gradually". A
 * deterministic local digest satisfies both: it walks the history turn by turn
 * on-device, always terminates, needs no network, and produces a small,
 * sendable summary. [RescueDigest] already encapsulates the compression policy
 * (verbatim user intent + one-line tool ledger + verbatim identifiers/errors +
 * verbatim tail); this object only adapts on-disk `parts_json` into its input.
 *
 * Kept pure (input = role + parts_json strings, output = digest string) so it
 * is unit-testable on the JVM with no Room/Android/ViewModel. The caller
 * (SessionListViewModel) loads the rows and writes the resulting
 * CompactMarkerEntity.
 *
 * ## On-disk shape parsed here (mirrors ChatViewModel's persist writers)
 *   text:       {"type":"text","value":"…"}
 *   toolUse:    {"type":"toolUse","value":{"toolUseId","name","input"(string),"description",…}}
 *   toolResult: {"type":"toolResult","value":{"toolUseId","name","output","success",…}}
 *   mediaRef:   {"type":"mediaRef",…}                          (ignored — no text)
 * Tool results are persisted on a `user`-role row; they are re-attached to
 * their originating tool_use by `toolUseId`, exactly like the live rescue path.
 */
object SessionCompressor {

    /** One persisted row, flattened by the caller. */
    data class Row(val role: String, val partsJson: String)

    /**
     * Build a rescue digest for the whole session. Returns "" when there is no
     * usable text (caller then leaves the session untouched rather than writing
     * an empty marker).
     */
    fun buildDigest(rows: List<Row>, maxChars: Int = RescueDigest.DEFAULT_MAX_CHARS): String {
        val turns = toTurns(rows)
        if (turns.isEmpty()) return ""
        return RescueDigest.build(turns, maxChars)
    }

    /** Map DB rows → RescueTurns, pairing tool results with their tool_use. */
    fun toTurns(rows: List<Row>): List<RescueDigest.RescueTurn> {
        // First pass: collect every tool result by id so a tool_use can find
        // its outcome no matter which later row carried it.
        val results = HashMap<String, ToolResultData>()
        for (row in rows) {
            forEachPart(row.partsJson) { type, part ->
                if (type == "toolResult") {
                    val value = part.optJSONObject("value") ?: return@forEachPart
                    val id = value.optString("toolUseId", "")
                    if (id.isNotEmpty()) {
                        results[id] = ToolResultData(
                            output = value.optString("output", ""),
                            // success:true → not an error. Absent → assume ok.
                            isError = !value.optBoolean("success", true),
                        )
                    }
                }
            }
        }

        val turns = mutableListOf<RescueDigest.RescueTurn>()
        for (row in rows) {
            val textSb = StringBuilder()
            val calls = mutableListOf<RescueDigest.RescueToolCall>()
            forEachPart(row.partsJson) { type, part ->
                when (type) {
                    "text" -> {
                        // A text part's `value` is a bare STRING, not an object.
                        val v = part.optString("value", "")
                        if (v.isNotBlank()) {
                            if (textSb.isNotEmpty()) textSb.append('\n')
                            textSb.append(v)
                        }
                    }
                    "toolUse" -> {
                        val value = part.optJSONObject("value") ?: return@forEachPart
                        val id = value.optString("toolUseId", "")
                        val res = results[id]
                        calls.add(
                            RescueDigest.RescueToolCall(
                                name = value.optString("name", "tool"),
                                argsPreview = argsPreview(value),
                                result = res?.output ?: "",
                                isError = res?.isError == true,
                            )
                        )
                    }
                    // toolResult already folded into its tool_use; mediaRef has
                    // no digestible text.
                    else -> Unit
                }
            }
            if (textSb.isBlank() && calls.isEmpty()) continue
            // A row that is ONLY tool results (persisted as a user row) must not
            // masquerade as a fresh user turn — that would pollute the "user
            // intent" section with tool output. Such a row has no text and no
            // tool_use of its own here (its results were attached to the
            // assistant's tool_use above), so it was already skipped by the
            // isBlank && isEmpty guard. Remaining rows carry real content.
            val role = when (row.role.lowercase()) {
                "assistant" -> RescueDigest.RescueTurn.Role.ASSISTANT
                "tool" -> RescueDigest.RescueTurn.Role.TOOL
                else -> RescueDigest.RescueTurn.Role.USER
            }
            turns.add(
                RescueDigest.RescueTurn(role = role, text = textSb.toString(), tools = calls)
            )
        }
        return turns
    }

    private data class ToolResultData(val output: String, val isError: Boolean)

    /**
     * The one identifying argument for the ledger. `input` is stored as an
     * escaped JSON STRING inside the toolUse value (see ChatViewModel persist
     * writers), so parse it defensively. Ordered by how much it tells a reader.
     */
    private fun argsPreview(toolUseValue: JSONObject): String {
        val input: JSONObject = when (val raw = toolUseValue.opt("input")) {
            is JSONObject -> raw
            is String -> if (raw.isBlank()) JSONObject() else try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            else -> JSONObject()
        }
        for (key in listOf("command", "path", "url", "query", "keywords", "selector", "text")) {
            val v = input.optString(key, "")
            if (v.isNotBlank()) return if (key == "command") v else "$key=$v"
        }
        // Unknown tool: a short raw preview so the row is still recognisable.
        val s = input.toString()
        return if (s.length <= 2) "" else s.take(110)
    }

    /** Iterate `{type,value}` parts of a parts_json array; malformed → no-op. */
    private inline fun forEachPart(partsJson: String, block: (type: String, part: JSONObject) -> Unit) {
        val arr = try { JSONArray(partsJson) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            block(o.optString("type"), o)
        }
    }
}
