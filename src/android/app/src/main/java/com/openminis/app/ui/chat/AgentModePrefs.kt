package com.openminis.app.ui.chat

import android.content.Context

/**
 * [A1] Remembers the composer's "Agents" toggle per chat.
 *
 * Per chat, not global: one conversation can be a long agent-driven task while
 * another is a quick question, and a global switch would make the expensive mode
 * leak into chats where the user never asked for it.
 *
 * Persisted rather than held in the ViewModel because Android kills this process
 * freely. A run takes minutes; coming back to find the toggle silently off —
 * after the user turned it on and sent nothing yet — reads as a broken button.
 *
 * Default is OFF. The toggle costs real money per turn, so it must never be on
 * by accident.
 *
 * Split into [Logic] plus a thin SharedPreferences adapter on purpose: unit
 * tests here run with `unitTests.isReturnDefaultValues = true` and no
 * Robolectric, so a real Context hands back a null SharedPreferences and any
 * test touching it dies with an NPE in CI. [Logic] has the rules and is tested;
 * this object only supplies storage.
 */
object AgentModePrefs {

    private const val PREFS = "agent_mode_prefs"

    /** Minimal storage seam so [Logic] can be exercised without Android. */
    internal interface Store {
        fun get(key: String): Boolean
        fun put(key: String, value: Boolean)
        fun remove(key: String)
    }

    /** The whole rule set, free of Android types. */
    internal object Logic {
        private const val KEY_PREFIX = "force_agents_"

        fun keyFor(sessionId: String): String = KEY_PREFIX + sessionId

        fun isForced(store: Store, sessionId: String): Boolean {
            if (sessionId.isBlank()) return false
            return store.get(keyFor(sessionId))
        }

        fun setForced(store: Store, sessionId: String, forced: Boolean) {
            if (sessionId.isBlank()) return
            // Removing instead of writing `false` keeps this file from growing
            // one dead entry per chat ever opened: false is already the default,
            // so a stored false carries no information.
            if (forced) store.put(keyFor(sessionId), true)
            else store.remove(keyFor(sessionId))
        }

        /**
         * Carry the flag from a draft id ("__new__…") to the real session id.
         *
         * A draft chat has no database row yet, so its toggle is stored under the
         * draft key. Without this, turning Agents on before the first message
         * would persist under an id that stops existing the moment the session is
         * created: right for this process, wrong after the process died. Same
         * class of bug as the orphaned draft workspace resources.
         */
        fun migrate(store: Store, fromDraft: String, toReal: String) {
            if (fromDraft == toReal) return
            if (isForced(store, fromDraft)) setForced(store, toReal, true)
            setForced(store, fromDraft, false)
        }
    }

    private fun storeFor(context: Context): Store {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return object : Store {
            override fun get(key: String): Boolean = prefs.getBoolean(key, false)
            // commit(), not apply(): the reason this flag is on disk at all is to
            // survive the process being killed, and apply() can lose a write that
            // is still queued when that happens. One boolean is a sub-millisecond
            // write on a user tap, so the synchronous cost is not worth the race.
            // It also keeps the sandbox parser-scan honest — without the Android
            // SDK, `editor.apply()` resolves to stdlib's `apply { block }` and
            // reports a false "no value passed for parameter 'block'".
            override fun put(key: String, value: Boolean) {
                prefs.edit().putBoolean(key, value).commit()
            }
            override fun remove(key: String) {
                prefs.edit().remove(key).commit()
            }
        }
    }

    fun isForced(context: Context, sessionId: String): Boolean =
        Logic.isForced(storeFor(context), sessionId)

    fun setForced(context: Context, sessionId: String, forced: Boolean) =
        Logic.setForced(storeFor(context), sessionId, forced)

    /** Drop the flag when a chat is deleted, so ids never accumulate. */
    fun clear(context: Context, sessionId: String) = setForced(context, sessionId, false)

    fun migrate(context: Context, fromDraft: String, toReal: String) =
        Logic.migrate(storeFor(context), fromDraft, toReal)
}
