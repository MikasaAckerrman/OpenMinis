package com.openminis.app.ui.settings

import java.io.File
import org.json.JSONObject

/**
 * [T-telegram-login-state] The state of the Telegram sign-in, in a form that
 * survives the screen being rebuilt or the process being killed.
 *
 * WHY THIS EXISTS. Every field of the login screen used to live in
 * `remember { mutableStateOf(...) }`. That is memory of one composition:
 * rotate the device, leave the screen, or let the process be reclaimed and
 * the phone number, the verification code and the phase of the flow are
 * gone. The user then has to start from the phone number again while a
 * code is already waiting in Telegram - and a login code is valid for a
 * few minutes only.
 *
 * WHAT IS STORED. The phone number, the API credentials, the verification
 * code and the phase of the flow. The cloud password is NOT stored: it is
 * the account's second factor, it is typed once, and it has no reason to
 * outlive the screen.
 *
 * The mapping below is deliberately free of Android types so it can be
 * unit tested on the JVM.
 */
data class TelegramLoginState(
    val phone: String = "",
    val code: String = "",
    val apiId: String = "",
    val apiHash: String = "",
    /** The code was already sent to Telegram; the flow is at the code step. */
    val codeSent: Boolean = false,
    /** The account has a cloud password; the flow is at the password step. */
    val needsPassword: Boolean = false,
) {
    /**
     * Nothing worth keeping: the flow can start from scratch.
     *
     * The phase flags count as content. A state that only says "a code was
     * already sent" still carries the progress, and dropping it would send
     * the user back to the phone number while a code is waiting.
     */
    val isEmpty: Boolean
        get() = phone.isBlank() && code.isBlank() && apiId.isBlank() && apiHash.isBlank() &&
            !codeSent && !needsPassword

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PHONE, phone)
        put(KEY_CODE, code)
        put(KEY_API_ID, apiId)
        put(KEY_API_HASH, apiHash)
        put(KEY_CODE_SENT, codeSent)
        put(KEY_NEEDS_PASSWORD, needsPassword)
    }

    companion object {
        private const val KEY_PHONE = "phone"
        private const val KEY_CODE = "code"
        private const val KEY_API_ID = "api_id"
        private const val KEY_API_HASH = "api_hash"
        private const val KEY_CODE_SENT = "code_sent"
        private const val KEY_NEEDS_PASSWORD = "needs_password"

        /**
         * Read the state back. A missing or broken file means "no state",
         * not a crash: a half written file must not lock the user out of
         * the sign-in screen.
         */
        fun fromJson(raw: String?): TelegramLoginState {
            if (raw.isNullOrBlank()) return TelegramLoginState()
            return try {
                val json = JSONObject(raw)
                TelegramLoginState(
                    phone = json.optString(KEY_PHONE, ""),
                    code = json.optString(KEY_CODE, ""),
                    apiId = json.optString(KEY_API_ID, ""),
                    apiHash = json.optString(KEY_API_HASH, ""),
                    codeSent = json.optBoolean(KEY_CODE_SENT, false),
                    needsPassword = json.optBoolean(KEY_NEEDS_PASSWORD, false),
                )
            } catch (_: Exception) {
                TelegramLoginState()
            }
        }
    }
}

/**
 * [T-telegram-login-state] Where the login state lives.
 *
 * The file sits in the app's private files directory, so the phone number
 * and the API credentials never leave the app's own storage. Writes go
 * through a temporary file and a rename, so a process kill in the middle
 * of a write leaves the previous state intact instead of a truncated file.
 */
class TelegramLoginStafun save(state: TelegramLoginState) {
        // The vault directory is created by the vault manager, but the
        // sign-in screen can run before it. Create it here so the first
        // save cannot fail on a missing parent.
        file.parentFile?.mkdirs()
        if (state.isEmpty) {
te = try {
        TelegramLoginState.fromJson(file.readText())
    } catch (_: Exception) {
        TelegramLoginState()
    }

    fun save(state: TelegramLoginState) {
        if (state.isEmpty) {
            clear()
            return
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(state.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(state.toJson().toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Losing the state must never break the sign-in screen.
        }
    }

    /** Called after a successful sign-in: nothing of the flow is kept. */
    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
            // Nothing to do: a stale file is harmless, it is replaced later.
        }
    }
}
