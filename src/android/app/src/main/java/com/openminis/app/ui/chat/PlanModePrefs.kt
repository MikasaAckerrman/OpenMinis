package com.openminis.app.ui.chat

import android.content.Context

/**
 * [T-plan-mode] Remembers PLAN mode per chat.
 *
 * Per chat, not global: one conversation can be a
 * cautious "show me the plan first" task while another runs free, and a
 * global switch would leak the restriction into chats that never asked for
 * it. Persisted rather than held in the ViewModel because Android kills
 * this process freely; coming back to find plan mode silently off — after
 * the user armed it and sent nothing yet — reads as a broken button.
 *
 * Default is OFF: plan mode restricts the agent, so it must never be on by
 * accident.
 *
 * Split into [Logic] plus a thin SharedPreferences adapter on purpose
 * (split discipline): unit tests run with
 * `unitTests.isReturnDefaultValues = true` and no Robolectric, so [Logic]
 * carries the rules and is tested; this object only supplies storage.
 */
object PlanModePrefs {

    private const val PREFS = "plan_mode_prefs"

    /** Minimal storage seam so [Logic] can be exercised without Android. */
    internal interface Store {
        fun get(key: String): Boolean
        fun put(key: String, value: Boolean)
        fun remove(key: String)
    }

    /** The whole rule set, free of Android types. */
    internal object Logic {
        private const val KEY_PREFIX = "plan_mode_"

        fun keyFor(sessionId: String): String = KEY_PREFIX + sessionId

        fun isEnabled(store: Store, sessionId: String): Boolean {
            if (sessionId.isBlank()) return false
            return store.get(keyFor(sessionId))
        }

        fun setEnabled(store: Store, sessionId: String, enabled: Boolean) {
            if (sessionId.isBlank()) return
            // Removing instead of writing `false` keeps this file from
            // growing one dead entry per chat ever opened: false is already
            // the default, so a stored false carries no information.
            if (enabled) store.put(keyFor(sessionId), true)
            else store.remove(keyFor(sessionId))
        }

        /**
         * Carry the flag from a draft id ("__new__…") to the real session
         * id — same reasoning as AgentModePrefs.migrate: a draft has no
         * database row, so arming plan mode before the first message would
         * otherwise persist under an id that stops existing.
         */
        fun migrate(store: Store, fromDraft: String, toReal: String) {
            if (fromDraft == toReal) return
            if (isEnabled(store, fromDraft)) setEnabled(store, toReal, true)
            setEnabled(store, fromDraft, false)
        }
    }

    private fun storeFor(context: Context): Store {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return object : Store {
            override fun get(key: String): Boolean = prefs.getBoolean(key, false)
            // commit(), not apply(): the reason this flag is on disk at all
            // is to survive the process being killed (mirrors AgentModePrefs).
            override fun put(key: String, value: Boolean) {
                prefs.edit().putBoolean(key, value).commit()
            }
            override fun remove(key: String) {
                prefs.edit().remove(key).commit()
            }
        }
    }

    fun isEnabled(context: Context, sessionId: String): Boolean =
        Logic.isEnabled(storeFor(context), sessionId)

    fun setEnabled(context: Context, sessionId: String, enabled: Boolean) =
        Logic.setEnabled(storeFor(context), sessionId, enabled)

    /** Drop the flag when a chat is deleted, so ids never accumulate. */
    fun clear(context: Context, sessionId: String) = setEnabled(context, sessionId, false)

    fun migrate(context: Context, fromDraft: String, toReal: String) =
        Logic.migrate(storeFor(context), fromDraft, toReal)
}
