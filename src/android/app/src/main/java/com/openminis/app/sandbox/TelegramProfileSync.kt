package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.net.Socket
import org.json.JSONObject

/**
 * [B42-task4] Fills the Kotlin account registry's blank profile fields
 * (firstName/username/userId) from the live vault server.
 *
 * ## Why this exists
 *
 * The migrated legacy account registers as "pending" with every profile
 * field blank ([TelegramAccountStore.migrateLegacyIfNeeded]), and the
 * settings screen rendered it as "Account / ?" forever. The vault server
 * DOES answer `tg_whoami` (grammers get_me on the Rust side) — but its
 * side-effect write lands in the DAEMON's own storage accounts.json, a
 * different file in a different shape from the app registry
 * ([TelegramAccountStore]). The bridge between the two has to be the
 * app: one tools/call per settings-screen open, one upsert.
 *
 * The transport is the same authenticated loopback gate the liveness
 * probe uses (127.0.0.1:7824, bearer from the vault auth.key). The tool
 * name tries the Rust generation first (`tg_whoami`) and falls back to
 * the python-era name (`tg_get_me`) so the sync works against whichever
 * server generation answers the port. Every failure degrades to "no
 * sync" — the screen keeps showing what it has; this helper never throws.
 */
object TelegramProfileSync {
    private const val TAG = "TgProfileSync"
    private const val PORT = 7824
    private const val TIMEOUT_MS = 4_000

    /** The account store path — the same registry the settings screen reads. */
    private fun vaultDir(context: Context): File = File(context.filesDir, "tg-vault")

    /**
     * Context convenience overload: the real vault registry plus the real
     * loopback transport. Call from Dispatchers.IO — it does blocking I/O.
     */
    fun syncActiveProfile(context: Context): TgAccount? =
        syncActiveProfile(vaultDir(context)) { tool -> vaultToolCall(context, tool) }

    /**
     * The injectable core: read the ACTIVE account, ask the server for its
     * live profile, upsert the enrichment. Returns the enriched account,
     * or null when there is nothing to do (no active account, dead server,
     * unrecognized answer) or when the profile was already complete.
     *
     * [transport] maps a tool name to the raw HTTP response body (null =
     * the call failed); the two-name fallback lives here so tests can
     * script both generations.
     */
    fun syncActiveProfile(
        vaultDir: File,
        transport: (tool: String) -> String?,
    ): TgAccount? {
        val store = TelegramAccountStore(vaultDir)
        val active = store.findActive() ?: return null
        // Rust generation first; a dead transport OR an unknown-tool
        // JSON-RPC error (-32601) falls back to the python-era name —
        // whichever server generation answers the port must work.
        val body = run {
            val first = transport("tg_whoami")
            if (first != null && !isUnknownTool(first)) {
                first
            } else {
                transport("tg_get_me") ?: first
            }
        } ?: return null
        val profile = extractProfileJson(body) ?: return null
        val enriched = enriched(active, profile)
        if (enriched == active) return null // already complete — no registry churn
        store.upsert(enriched)
        Log.i(
            TAG,
            "profile enriched: user=${enriched.userId} name=${enriched.firstName} " +
                "@${enriched.username} (was pending=${active.userId == null})",
        )
        return enriched
    }

    /**
     * JSON-RPC `tools/call` over the vault gate — same wire shape as
     * [TelegramVaultManager.isAlive] (bearer header when armed, /mcp path,
     * byte-exact Content-Length). Null on any transport failure.
     */
    private fun vaultToolCall(context: Context, tool: String): String? = try {
        val auth = TelegramVaultManager.authSecret(context)
        val authHeader = auth.takeIf { it.isNotEmpty() }
            ?.let { "Authorization: Bearer $it\r\n" } ?: ""
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 9)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", tool)
                    .put("arguments", JSONObject()),
            )
            .toString()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        Socket("127.0.0.1", PORT).use { s ->
            s.soTimeout = TIMEOUT_MS
            val request = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                authHeader +
                "Connection: close\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n\r\n"
            s.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                write(bodyBytes)
                flush()
            }
            s.getInputStream().bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "vault tools/call($tool) failed: ${e.message}")
        null
    }

    /**
     * The MCP envelope: the profile rides `result.content[0].text` (the
     * Rust server serializes the tool's JSON answer into the text part).
     * Python-era servers answered the profile object as the result itself —
     * tolerated. Null when neither shape is present.
     */
    internal fun extractProfileJson(httpBody: String): JSONObject? {
        val body = httpBody.substringAfter("\r\n\r\n", "")
            .ifBlank { httpBody.substringAfter("\n\n", httpBody) }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val result = json.optJSONObject("result") ?: return null
        val content = result.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val text = content.optJSONObject(0)?.optString("text")
            if (!text.isNullOrBlank()) {
                runCatching { return JSONObject(text) }
            }
        }
        if (result.has("userId") || result.has("user_id")) return result
        return null
    }

    /** The JSON-RPC "no such tool" verdict (-32601) — the fallback trigger. */
    internal fun isUnknownTool(httpBody: String): Boolean {
        val body = httpBody.substringAfter("\r\n\r\n", "")
            .ifBlank { httpBody.substringAfter("\n\n", httpBody) }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return json.optJSONObject("error")?.optInt("code", 0) == -32601
    }

    /**
     * Overlay the live profile onto the registry entry: the server's
     * values win when present; blank server fields never erase what the
     * registry already holds (the phone, in particular, is login-side
     * data the profile API does not carry).
     */
    internal fun enriched(base: TgAccount, profile: JSONObject): TgAccount {
        val userId = if (profile.has("userId") && !profile.isNull("userId")) {
            profile.optLong("userId")
        } else {
            base.userId
        }
        return base.copy(
            userId = userId,
            username = profile.optString("username").ifBlank { base.username },
            firstName = profile.optString("firstName").ifBlank { base.firstName },
        )
    }
}
