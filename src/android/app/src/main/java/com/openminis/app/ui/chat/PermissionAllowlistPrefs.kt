package com.openminis.app.ui.chat

import android.content.Context
import java.security.MessageDigest

/**
 * [T-permission-modes] Learned approvals for EDIT mode: a tool call the
 * user answered "Always allow" runs without asking again for the rest of
 * the session.
 *
 * Scope is deliberately tight: per session (a learned allowance never
 * leaks into another chat) and per exact argument payload (hashed, so
 * "always allow `pip install requests`" does not silently cover
 * `pip install requests && rm -rf /`). Loosening the matching to command
 * heads would trade the user's one tap for a standing grant they never
 * consciously gave — the exact trade this gate exists to avoid.
 *
 * Split into [Logic] plus a thin SharedPreferences adapter (same
 * discipline as the other prefs objects): [Logic] carries the rules and
 * is tested without Android.
 */
object PermissionAllowlistPrefs {

    private const val PREFS = "permission_allowlist_prefs"

    /** Minimal storage seam so [Logic] can be exercised without Android. */
    internal interface Store {
        fun getStringSet(key: String): Set<String>
        fun putStringSet(key: String, values: Set<String>)
    }

    internal object Logic {
        private const val KEY_PREFIX = "allow_"

        fun keyFor(sessionId: String): String = KEY_PREFIX + sessionId

        /**
         * One entry = tool name + SHA-256 of the raw arguments JSON. The
         * hash (not the raw JSON) keeps the prefs set compact and keeps
         * argument noise out of what is effectively a security decision.
         */
        fun entry(toolName: String, argsJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(argsJson.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "$toolName\u0000$hex"
        }

        fun isAllowed(store: Store, sessionId: String, toolName: String, argsJson: String): Boolean {
            if (sessionId.isBlank()) return false
            return store.getStringSet(keyFor(sessionId)).contains(entry(toolName, argsJson))
        }

        fun allow(store: Store, sessionId: String, toolName: String, argsJson: String) {
            if (sessionId.isBlank()) return
            val key = keyFor(sessionId)
            store.putStringSet(key, store.getStringSet(key) + entry(toolName, argsJson))
        }

        fun clear(store: Store, sessionId: String) {
            if (sessionId.isBlank()) return
            store.putStringSet(keyFor(sessionId), emptySet())
        }

        fun migrate(store: Store, fromDraft: String, toReal: String) {
            if (fromDraft == toReal) return
            val carried = store.getStringSet(keyFor(fromDraft))
            if (carried.isNotEmpty()) {
                store.putStringSet(keyFor(toReal), store.getStringSet(keyFor(toReal)) + carried)
            }
            store.putStringSet(keyFor(fromDraft), emptySet())
        }
    }

    private fun storeFor(context: Context): Store {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return object : Store {
            override fun getStringSet(key: String): Set<String> =
                prefs.getStringSet(key, emptySet()) ?: emptySet()
            // commit(), not apply(): a learned approval must not evaporate
            // if the process dies before the write lands (mirrors the other
            // prefs objects).
            override fun putStringSet(key: String, values: Set<String>) {
                prefs.edit().putStringSet(key, values).commit()
            }
        }
    }

    fun isAllowed(context: Context, sessionId: String, toolName: String, argsJson: String): Boolean =
        Logic.isAllowed(storeFor(context), sessionId, toolName, argsJson)

    fun allow(context: Context, sessionId: String, toolName: String, argsJson: String) =
        Logic.allow(storeFor(context), sessionId, toolName, argsJson)

    fun clear(context: Context, sessionId: String) = Logic.clear(storeFor(context), sessionId)

    fun migrate(context: Context, fromDraft: String, toReal: String) =
        Logic.migrate(storeFor(context), fromDraft, toReal)
}
