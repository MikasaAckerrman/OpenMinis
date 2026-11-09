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
    /**
     * [B44-multiaccount] Search/Read priority: LOWER value = used FIRST
     * (drag-to-top sets 0..n in UI order). Legacy rows written before the
     * field existed load with 0 and tie-break by [addedAt], so an old
     * registry keeps its relative order with zero migration code.
     */
    val priority: Int = 0,
    /**
     * [B44-multiaccount] Participation switch: a disabled account is
     * skipped by the search/read routing entirely (the session file and
     * the registry entry stay untouched — the toggle is not an unlink).
     */
    val isEnabled: Boolean = true,
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
                    // [B44-multiaccount] Missing fields on a pre-B44 row
                    // degrade to the legacy-stable defaults (0 / true).
                    priority = o.optInt("priority", 0),
                    isEnabled = o.optBoolean("isEnabled", true),
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
                put("priority", a.priority)
                put("isEnabled", a.isEnabled)
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

    /**
     * [B17-C] User-initiated unbind — the ONLY unlink path. Agents have no
     * tool that removes accounts (by design: a compromised agent session
     * must not be able to wipe the user's Telegram identities); this is
     * called exclusively from the settings screen behind a confirm dialog.
     * Removes the registry entry and the account's session directory. The
     * legacy "pending" account's session lives at the CLASSIC vault path
     * (stage-A layout), so unlinking it deletes that file too — otherwise
     * the unbind would be a fiction and the next migrateLegacyIfNeeded()
     * would resurrect the account.
     */
    fun remove(dirName: String): Boolean {
        val accounts = load()
        val target = accounts.firstOrNull { it.dirName == dirName } ?: return false
        save(accounts.filter { it.dirName != dirName })
        val dir = File(accountsDir, dirName)
        if (dir.exists()) dir.deleteRecursively()
        if (dirName == "pending") {
            File(vaultDir, "tg_session.session").delete()
        }
        return true
    }

    /** Exclusive active flag: exactly one account (or none) is active. */
    fun setActive(dirName: String) {
        save(load().map { it.copy(isActive = it.dirName == dirName) })
    }

    fun findActive(): TgAccount? = load().firstOrNull { it.isActive }

    /**
     * [B44-multiaccount] The search/read routing view: enabled accounts
     * in priority order (drag-to-top = lowest number = first), ties
     * broken by registration time so a legacy registry keeps its shape.
     * The daemon mirrors this exact ordering when it walks accounts.
     */
    fun orderedForSearch(): List<TgAccount> =
        load().filter { it.isEnabled }.sortedWith(
            compareBy({ it.priority }, { it.addedAt }, { it.dirName }),
        )

    /** UI reorder: write the drag result as explicit priorities 0..n-1. */
    fun applyOrder(orderedDirNames: List<String>) {
        val priorityOf = orderedDirNames.withIndex().associate { (i, name) -> name to i }
        save(load().map { it.copy(priority = priorityOf[it.dirName] ?: it.priority) })
    }

    /** [B44-multiaccount] The participation toggle — NOT an unlink. */
    fun setEnabled(dirName: String, enabled: Boolean) {
        save(load().map { if (it.dirName == dirName) it.copy(isEnabled = enabled) else it })
    }

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
