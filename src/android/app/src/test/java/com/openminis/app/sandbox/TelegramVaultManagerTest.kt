package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-tg-vault] Contract tests for the vault migration logic — the part
 * that must NEVER lose or leak the session file. These tests run on a
 * FAKE tree (temporary folders mimicking filesDir), not on real app data:
 * the real launch path needs a booted PRoot, which unit tests don't have.
 */
class TelegramVaultManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeTree(): Triple<File, File, File> {
        val files = tmp.newFolder("files")
        val src = File(files, "minis-global/mcp-servers/srv").apply { mkdirs() }
        val vault = File(files, "tg-vault")
        return Triple(files, src, vault)
    }

    @Test
    fun `moved session is deleted from the agent-visible side`() {
        val (files, src, _) = makeTree()
        val session = File(src, "tg_session.session").apply { writeText("SESSION-DATA") }
        TelegramVaultManager.migrateSessionForTest(src, files)
        // after migration the vault holds the data...
        val vaultFile = File(File(files, "tg-vault"), "tg_session.session")
        assertTrue("vault must hold the session", vaultFile.exists())
        assertEquals("SESSION-DATA", vaultFile.readText())
        // ...and the agent-visible copy is GONE — the whole point.
        assertFalse("agent-visible session must be gone", session.exists())
    }

    @Test
    fun `journal travels with the session and the old path returns ENOENT`() {
        val (files, src, _) = makeTree()
        val session = File(src, "tg_session.session").apply { writeText("SESSION-DATA") }
        val journal = File(src, "tg_session.session-journal").apply { writeText("WAL-DATA") }
        TelegramVaultManager.migrateSessionForTest(src, files)
        val vault = File(files, "tg-vault")
        assertTrue("session must be in the vault", File(vault, "tg_session.session").exists())
        assertTrue("journal must travel too", File(vault, "tg_session.session-journal").exists())
        assertFalse("old session path must be gone (ENOENT)", session.exists())
        assertFalse("old journal path must be gone", journal.exists())
    }

    @Test
    fun `stale duplicate on the agent side is removed, vault copy wins`() {
        val (files, src, _) = makeTree()
        val vault = File(files, "tg-vault").apply { mkdirs() }
        File(vault, "tg_session.session").writeText("AUTHORITATIVE")
        val stale = File(src, "tg_session.session").apply { writeText("STALE-LEAK") }
        TelegramVaultManager.migrateSessionForTest(src, files)
        assertFalse("stale agent-visible copy must be deleted", stale.exists())
        assertEquals("AUTHORITATIVE", File(vault, "tg_session.session").readText())
    }

    // ── [M-R3.1] vault_impl flag ────────────────────────────────────────

    @Test
    fun `missing flag file defaults to the python vault`() {
        val vault = tmp.newFolder("tg-vault")
        // The default keeps a logged-in user's surface alive: the in-app
        // login still produces a pyrogram session, and the Rust daemon
        // reads only grammers (spec §2.3 — no converter exists).
        assertEquals("python", TelegramVaultManager.activeVaultImpl(vault))
    }

    @Test
    fun `flag file rust selects the daemon`() {
        val vault = tmp.newFolder("tg-vault")
        File(vault, "vault_impl").writeText("rust")
        assertEquals("rust", TelegramVaultManager.activeVaultImpl(vault))
    }

    @Test
    fun `flag parsing is whitespace and case tolerant, deny-by-default`() {
        assertEquals("rust", TelegramVaultManager.parseVaultImpl("rust"))
        assertEquals("rust", TelegramVaultManager.parseVaultImpl(" RUST\n"))
        assertEquals("python", TelegramVaultManager.parseVaultImpl("python"))
        // Anything else — garbage, typos, an empty file, null — stays on
        // the legacy path: a half-flipped switch must not flip the server.
        assertEquals("python", TelegramVaultManager.parseVaultImpl("rus"))
        assertEquals("python", TelegramVaultManager.parseVaultImpl("rust python"))
        assertEquals("python", TelegramVaultManager.parseVaultImpl(""))
        assertEquals("python", TelegramVaultManager.parseVaultImpl(null))
    }

    @Test
    fun `unreadable flag file degrades to python instead of crashing boot`() {
        val vault = tmp.newFolder("tg-vault")
        // A directory where the flag file should be: readText() throws.
        File(vault, "vault_impl").mkdir()
        assertEquals("python", TelegramVaultManager.activeVaultImpl(vault))
    }
}
