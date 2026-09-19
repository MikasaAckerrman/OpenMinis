package com.openminis.app.service

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * [T-rewrite-stealth] Persistent, UI-only "edited" marks for messages whose
 * text was rewritten via MessageSurgery (the in-chat edit/rewrite flow).
 *
 * DESIGN CONSTRAINTS:
 *  - The DB `messages` table schema is NOT touched (no Room migration):
 *    the mark lives in a SharedPreferences JSON index instead.
 *  - The LLM/agent NEVER sees this mark: it is not part of parts_json, not
 *    part of ChatMessage → LLMMessage conversion, and no system-reminder is
 *    injected when a message is rewritten. The model simply reads the NEW
 *    text from parts_json on the next turn — silently.
 *  - Survives process death and session reload: loadSession reads the mark
 *    back so the pencil indicator reappears after reopening a session.
 *
 * Storage shape (single JSON object, one entry per rewritten row):
 *   { "<sessionId>::<rowId>": <editedAtMs>, ... }
 *
 * Capped to the most recent [MAX_ENTRIES] entries (LRU by timestamp) so the
 * prefs blob stays small even in long-lived installs.
 */
object MessageEditStore {

    private const val PREFS = "message_edit_marks"
    private const val KEY_INDEX = "edited_index"
    private const val MAX_ENTRIES = 2048

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("MessageEditStore not initialized")

    /** Record that [rowId] in [sessionId] was rewritten at [editedAtMs]. */
    fun markEdited(sessionId: String, rowId: String, editedAtMs: Long = System.currentTimeMillis()) {
        if (sessionId.isEmpty() || rowId.isEmpty()) return
        try {
            val obj = readIndex()
            obj.put(key(sessionId, rowId), editedAtMs)
            trim(obj)
            p().edit().putString(KEY_INDEX, obj.toString()).apply()
        } catch (_: Exception) {
            // Best-effort store: a failed mark only loses the pencil visual,
            // never the rewrite itself (which is already committed to the DB).
        }
    }

    /** True if this exact row was rewritten at some point. */
    fun isEdited(sessionId: String, rowId: String): Boolean {
        if (sessionId.isEmpty() || rowId.isEmpty()) return false
        return try {
            readIndex().has(key(sessionId, rowId))
        } catch (_: Exception) {
            false
        }
    }

    /** Timestamp of the last edit for this row, or null if never edited. */
    fun editedAt(sessionId: String, rowId: String): Long? {
        if (sessionId.isEmpty() || rowId.isEmpty()) return null
        return try {
            val obj = readIndex()
            if (obj.has(key(sessionId, rowId))) obj.getLong(key(sessionId, rowId)) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Drop every mark for a session (e.g. session deleted). */
    fun clearSession(sessionId: String) {
        if (sessionId.isEmpty()) return
        try {
            val obj = readIndex()
            val prefix = sessionId + "::"
            val it = obj.keys()
            var changed = false
            while (it.hasNext()) {
                val k = it.next()
                if (k.startsWith(prefix)) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) p().edit().putString(KEY_INDEX, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun key(sessionId: String, rowId: String) = "$sessionId::$rowId"

    private fun readIndex(): JSONObject {
        val raw = p().getString(KEY_INDEX, null) ?: return JSONObject()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    /** Keep only the newest [MAX_ENTRIES] entries (LRU by value timestamp). */
    private fun trim(obj: JSONObject) {
        if (obj.length() <= MAX_ENTRIES) return
        val entries = ArrayList<Pair<String, Long>>(obj.length())
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            entries.add(k to obj.optLong(k, 0L))
        }
        entries.sortByDescending { it.second }
        val keep = entries.take(MAX_ENTRIES).map { it.first }.toHashSet()
        val drop = entries.drop(MAX_ENTRIES)
        for (e in drop) obj.remove(e.first)
    }
}
