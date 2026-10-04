package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.engine.PermissionMode

/**
 * [T-permission-modes] Vocabulary of the EDIT-mode confirmation dialog —
 * one source of truth for the option chips the user taps and the verdicts
 * the executor matches against.
 */
object PermissionAskOptions {
    const val ALLOW = "Allow"
    const val ALWAYS = "Always allow"
    const val DENY = "Deny"
    val ALL = listOf(ALLOW, ALWAYS, DENY)
}

/**
 * [T-permission-modes] Remembers the session's permission mode per chat —
 * the ZCode plan/edit/yolo contract:
 *
 *  - [PermissionMode.PLAN] — read-only exploration until plan_submit
 *    approval (M2);
 *  - [PermissionMode.EDIT] — mutating tool calls surface a confirmation;
 *    "Always allow" learns the call for the session;
 *  - [PermissionMode.AUTO] — the historical default: everything runs, the
 *    sandbox plus the offload gates remain the containment.
 *
 * Persisted rather than held in the ViewModel because Android kills this
 * process freely (same reasoning as AgentModePrefs/PlanModePrefs). Split
 * into [Logic] plus a thin SharedPreferences adapter so [Logic] is
 * testable without Android.
 */
object PermissionModePrefs {

    private const val PREFS = "permission_mode_prefs"

    /** Minimal storage seam so [Logic] can be exercised without Android. */
    internal interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    /** The whole rule set, free of Android types. */
    internal object Logic {
        private const val KEY_PREFIX = "mode_"

        fun keyFor(sessionId: String): String = KEY_PREFIX + sessionId

        /** null = never set (the caller applies its default). */
        fun get(store: Store, sessionId: String): PermissionMode? {
            if (sessionId.isBlank()) return null
            val raw = store.get(keyFor(sessionId)) ?: return null
            // Garbage in prefs (hand-edited or a downgraded build) must
            // never crash the chat — fall through to the caller's default.
            return runCatching { PermissionMode.valueOf(raw) }.getOrNull()
        }

        fun set(store: Store, sessionId: String, mode: PermissionMode) {
            if (sessionId.isBlank()) return
            store.put(keyFor(sessionId), mode.name)
        }

        fun migrate(store: Store, fromDraft: String, toReal: String) {
            if (fromDraft == toReal) return
            val mode = get(store, fromDraft)
            if (mode != null) set(store, toReal, mode)
            store.remove(keyFor(fromDraft))
        }
    }

    private fun storeFor(context: Context): Store {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return object : Store {
            override fun get(key: String): String? = prefs.getString(key, null)
            // commit(), not apply(): the mode must survive the process being
            // killed (mirrors AgentModePrefs).
            override fun put(key: String, value: String) {
                prefs.edit().putString(key, value).commit()
            }
            override fun remove(key: String) {
                prefs.edit().remove(key).commit()
            }
        }
    }

    /**
     * Effective mode. Falls back to the M2 boolean plan flag once (sessions
     * created before the mode refactor), then to AUTO — plan mode must
     * never be on by accident, and neither must any other restriction.
     */
    fun get(context: Context, sessionId: String): PermissionMode =
        Logic.get(storeFor(context), sessionId)
            ?: if (PlanModePrefs.isEnabled(context, sessionId)) {
                PermissionMode.PLAN
            } else {
                PermissionMode.AUTO
            }

    fun set(context: Context, sessionId: String, mode: PermissionMode) =
        Logic.set(storeFor(context), sessionId, mode)

    /** Drop the entry when a chat is deleted, so ids never accumulate. */
    fun clear(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        storeFor(context).remove(Logic.keyFor(sessionId))
    }

    fun migrate(context: Context, fromDraft: String, toReal: String) =
        Logic.migrate(storeFor(context), fromDraft, toReal)
}
