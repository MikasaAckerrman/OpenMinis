package com.openminis.app.sandbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-tg-accounts] The Telegram MULTI-ACCOUNT registry (B17, stage A).
 *
 * Storage: filesDir/tg-vault/accounts.json —
 *   {"version":1,"accounts":[{...}]}
 * Each account owns a sub-directory accounts/<dirName>/ that will hold its
 * tg_session.session (stage B wires the multi-client server to it). The
 * ACTIVE account's session ALSO lives at the classic tg-vault/tg_session.session
 * path until stage B moves the server to per-account dirs — so today's
 * single-session vault server keeps working unchanged.
 *
 * Legacy migration: a session file from the single-account era is registered
 * as the active "pending" account (user_id is unknown until the first
 * successful get_me fills it in during stage B). Idempotent: a second run
 * must not duplicate the entry.
 */
data class TgAccount(
    val dirName: String,
    val userId: Long? = null,
    val phone: String? = null,
    val username: String? = null,
    val firstName: String? = null,
    val avatarPath: String? = null,
    val isActive: Boolean = false,
    val addedAt: Long = 0L,
)

class TelegramAccountStore(private val vaultDir: File) {

    private val registryFile: File get() = File(vaultDir, "accounts.json")
    val accountsDir: File get() = File(vaultDir, "accounts")

    fun load(): List<TgAccount> = try {
        if (!registryFile.exists()) {
            emptyList()
        } else {
            val root = JSONObject(registryFile.readText())
            val arr = root.optJSONArray("accounts") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TgAccount(
                    dirName = o.optString("dirName"),
                    userId = if (o.has("userId") && !o.isNull("userId")) o.optLong("userId") else null,
                    phone = o.optString("phone").ifBlank { null },
                    username = o.optString("username").ifBlank { null },
                    firstName = o.optString("firstName").ifBlank { null },
                    avatarPath = o.optString("avatarPath").ifBlank { null },
                    isActive = o.optBoolean("isActive", false),
                    addedAt = o.optLong("addedAt"),
                )
            }.filter { it.dirName.isNotBlank() }
        }
    } catch (e: Exception) {
        // A broken registry must never lock the user out of the sign-in.
        emptyList()
    }

    fun save(accounts: List<TgAccount>) {
        vaultDir.mkdirs()
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("dirName", a.dirName)
                if (a.userId != null) put("userId", a.userId) else put("userId", JSONObject.NULL)
                put("phone", a.phone ?: "")
                put("username", a.username ?: "")
                put("firstName", a.firstName ?: "")
                put("avatarPath", a.avatarPath ?: "")
                put("isActive", a.isActive)
                put("addedAt", a.addedAt)
            })
        }
        // tmp + rename: a kill mid-write leaves the previous registry intact.
        val tmp = File(vaultDir, "accounts.json.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("accounts", arr).toString(2))
        if (!tmp.renameTo(registryFile)) {
            registryFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun upsert(account: TgAccount) {
        save(load().filter { it.dirName != account.dirName } + account)
    }

    /** Exclusive active flag: exactly one account (or none) is active. */
    fun setActive(dirName: String) {
        save(load().map { it.copy(isActive = it.dirName == dirName) })
    }

    fun findActive(): TgAccount? = load().firstOrNull { it.isActive }

    /**
     * One-time legacy migration: register the single-account-era session as
     * the ACTIVE "pending" account. The session FILE itself is NOT moved —
     * the stage-A vault server still reads the classic path. Idempotent.
     */
    fun migrateLegacyIfNeeded(legacySession: File): TgAccount? {
        if (!legacySession.exists()) return null
        if (load().isNotEmpty()) return null // already migrated / accounts exist
        val account = TgAccount(
            dirName = "pending",
            isActive = true,
            addedAt = System.currentTimeMillis(),
        )
        save(listOf(account))
        File(accountsDir, "pending").mkdirs()
        return account
    }
}
