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
    fun `stale duplicate on the agent side is removed, vault copy wins`() {
        val (files, src, _) = makeTree()
        val vault = File(files, "tg-vault").apply { mkdirs() }
        File(vault, "tg_session.session").writeText("AUTHORITATIVE")
        val stale = File(src, "tg_session.session").apply { writeText("STALE-LEAK") }
        TelegramVaultManager.migrateSessionForTest(src, files)
        assertFalse("stale agent-visible copy must be deleted", stale.exists())
        assertEquals("AUTHORITATIVE", File(vault, "tg_session.session").readText())
    }
}
