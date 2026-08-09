package com.openminis.app.ui.chat

import org.json.JSONArray

/**
 * [T-android-chat-open-json-reparse] One-parse-per-row cache for a message's
 * `partsJson`.
 *
 * Why: opening a session parsed every row's `partsJson` THREE times —
 *   1. `toChatMessages` pass 1, collecting toolResult payloads by toolUseId,
 *   2. `toChatMessages` pass 2, building the UI bubble,
 *   3. `toLLMMessage`, building the LLM history entry.
 * `JSONArray(String)` re-tokenises the whole document each time, so on a
 * 700-message session that is ~2100 full parses plus the garbage they produce —
 * the open path's dominant cost, and the same shape as the earlier GC-storm
 * stall (405 messages / 58 s).
 *
 * This holds the parsed `JSONArray` for the rows of ONE load, keyed by message
 * id. Scoped to a single `loadSession` pass and dropped afterwards: keeping
 * parsed JSON for every message alive would trade a CPU problem for a memory
 * one, which is exactly what the unbounded ViewModel cache already taught us.
 *
 * Not thread-safe by design — a load runs on one dispatcher, and a shared
 * mutable cache across loads is precisely the lifetime bug to avoid.
 */
class PartsJsonCache(expectedRows: Int = 16) {

    private val parsed = HashMap<String, JSONArray?>(expectedRows.coerceAtLeast(1))

    /**
     * Parsed parts for [messageId], or null when [partsJson] is malformed.
     *
     * A malformed row caches its null too: every earlier call site swallowed the
     * exception and moved on, so re-parsing known-bad JSON would pay the throw
     * cost on each of the three passes for no new information.
     */
    fun get(messageId: String, partsJson: String): JSONArray? {
        if (parsed.containsKey(messageId)) return parsed[messageId]
        val value = try {
            JSONArray(partsJson)
        } catch (_: Exception) {
            null
        }
        parsed[messageId] = value
        return value
    }

    /** Number of rows parsed so far. Used by tests and perf breadcrumbs. */
    val size: Int get() = parsed.size

    /** Release the parsed documents once the load pass is done. */
    fun clear() {
        parsed.clear()
    }
}
