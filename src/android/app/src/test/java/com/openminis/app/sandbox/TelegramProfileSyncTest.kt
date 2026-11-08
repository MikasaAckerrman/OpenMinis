package com.openminis.app.sandbox

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [B42-task4] The registry-enrichment bridge: the vault's tg_whoami
 * answer lands in the app's accounts registry (the daemon's own
 * accounts.json write never reaches it — different file, different
 * shape). Pure-JVM: the loopback transport is injected.
 */
class TelegramProfileSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun envelope(profileJson: String): String {
        val body = JSONObject()
            .put(
                "result",
                JSONObject()
                    .put(
                        "content",
                        org.json.JSONArray().put(JSONObject().put("text", profileJson)),
                    ),
            )
            .toString()
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$body"
    }

    private fun rpcError(code: Int): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 9)
            .put("error", JSONObject().put("code", code).put("message", "unknown tool"))
            .toString()
        return "HTTP/1.1 200 OK\r\n\r\n$body"
    }

    private fun store(): TelegramAccountStore = TelegramAccountStore(tmp.root)

    private fun seedActive(vararg pairs: Pair<String, Any?>): TgAccount {
        val account = TgAccount(
            dirName = "pending",
            userId = pairs.firstOrNull { it.first == "userId" }?.second as? Long,
            phone = pairs.firstOrNull { it.first == "phone" }?.second as? String,
            username = pairs.firstOrNull { it.first == "username" }?.second as? String,
            firstName = pairs.firstOrNull { it.first == "firstName" }?.second as? String,
            isActive = true,
            addedAt = 1L,
        )
        store().upsert(account)
        return account
    }

    private val profile = JSONObject()
        .put("userId", 42L)
        .put("username", "johndoe")
        .put("firstName", "John")
        .put("lastName", "Doe")
        .toString()

    @Test
    fun `envelope answer enriches the pending account`() {
        seedActive()
        val out = TelegramProfileSync.syncActiveProfile(tmp.root) { envelope(profile) }
        assertNotNull(out)
        val saved = store().findActive()
        assertEquals(42L, saved?.userId)
        assertEquals("johndoe", saved?.username)
        assertEquals("John", saved?.firstName)
    }

    @Test
    fun `blank server fields never erase registry data`() {
        seedActive("phone" to "+79001234567", "firstName" to "Old")
        val sparse = JSONObject().put("userId", 42L).put("username", "").put("firstName", "").toString()
        TelegramProfileSync.syncActiveProfile(tmp.root) { envelope(sparse) }
        val saved = store().findActive()
        assertEquals("+79001234567", saved?.phone)
        assertEquals("Old", saved?.firstName)
    }

    @Test
    fun `already complete profile performs no registry write`() {
        seedActive("userId" to 42L, "username" to "johndoe", "firstName" to "John")
        var calls = 0
        val out = TelegramProfileSync.syncActiveProfile(tmp.root) { calls++; envelope(profile) }
        assertNull("no-op must not pretend to enrich", out)
        assertEquals(1, calls)
        assertEquals(1, store().load().size)
    }

    @Test
    fun `unknown tool falls back to the python-era name`() {
        seedActive()
        val answers = mapOf(
            "tg_whoami" to rpcError(-32601),
            "tg_get_me" to envelope(profile),
        )
        val seen = mutableListOf<String>()
        val out = TelegramProfileSync.syncActiveProfile(tmp.root) { tool ->
            seen += tool
            answers[tool]
        }
        assertNotNull(out)
        assertEquals(listOf("tg_whoami", "tg_get_me"), seen)
        assertEquals("johndoe", store().findActive()?.username)
    }

    @Test
    fun `dead transport degrades to no sync`() {
        seedActive("phone" to "+79001234567")
        val out = TelegramProfileSync.syncActiveProfile(tmp.root) { null }
        assertNull(out)
        val saved = store().findActive()
        assertEquals("+79001234567", saved?.phone)
        assertNull(saved?.username)
    }

    @Test
    fun `no active account means nothing to do`() {
        var calls = 0
        val out = TelegramProfileSync.syncActiveProfile(tmp.root) { calls++; null }
        assertNull(out)
        assertEquals(0, calls)
    }

    @Test
    fun `bare result object shape is tolerated`() {
        seedActive()
        val body = "HTTP/1.1 200 OK\r\n\r\n" +
            JSONObject().put("result", JSONObject(profile)).toString()
        TelegramProfileSync.syncActiveProfile(tmp.root) { body }
        assertEquals("johndoe", store().findActive()?.username)
    }

    @Test
    fun `parser refuses garbage and non-profile results`() {
        assertNull(TelegramProfileSync.extractProfileJson("not http at all"))
        assertNull(
            TelegramProfileSync.extractProfileJson(
                "HTTP/1.1 200 OK\r\n\r\n" + JSONObject().put("result", JSONObject().put("content", org.json.JSONArray())).toString(),
            ),
        )
        assertTrue(TelegramProfileSync.extractProfileJson(envelope(profile))!!.has("username"))
        // The -32601 verdict is what triggers the tg_get_me fallback.
        assertTrue(TelegramProfileSync.isUnknownTool(rpcError(-32601)))
        assertFalse(TelegramProfileSync.isUnknownTool(rpcError(-32000)))
        assertFalse(TelegramProfileSync.isUnknownTool(envelope(profile)))
    }
}
