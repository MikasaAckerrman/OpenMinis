package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-chat-link-global-fallback] `attachments`, `offloads`, `workspace` and
 * `browser` are bind-mounted per session under files/minis-sessions/<sid>/,
 * so an attachment produced by one session is not in another session's
 * directory. A chat link therefore has to fall back to the global pool.
 *
 * Verified on device before the fix: a 43 MB APK written at 09:06 existed
 * at 09:06, and the chat session's attachments directory was empty at
 * 09:18, so the link degraded to the in-app browser and surfaced as
 * "Blocked link to external app (minis)".
 */
class ChatLinkGlobalPoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun globalRoot(): File = File(tmp.root, "minis-global")

    private fun writeInGlobalPool(subdir: String, name: String): File {
        val dir = File(globalRoot(), subdir).apply { mkdirs() }
        return File(dir, name).apply { writeText("payload") }
    }

    @Test
    fun findsAnAttachmentWrittenByAnotherSession() {
        val written = writeInGlobalPool("attachments", "app.apk")

        val found = ChatLinkResolver.resolveInGlobalPool(
            globalRoot(),
            "minis://attachments/app.apk",
            "minis",
        )

        assertNotNull("a cross-session attachment must resolve", found)
        assertEquals(written.canonicalPath, found!!.canonicalPath)
    }

    @Test
    fun resolvesAnAbsoluteSandboxPathToo() {
        val written = writeInGlobalPool("workspace", "report.csv")

        val found = ChatLinkResolver.resolveInGlobalPool(
            globalRoot(),
            "minis:///var/minis/workspace/report.csv",
            "minis",
        )

        assertNotNull(found)
        assertEquals(written.canonicalPath, found!!.canonicalPath)
    }

    @Test
    fun eachPerSessionSubdirIsReachable() {
        for (subdir in ChatLinkResolver.PER_SESSION_SUBDIRS) {
            writeInGlobalPool(subdir, "file-$subdir.bin")
            val found = ChatLinkResolver.resolveInGlobalPool(
                globalRoot(),
                "minis://$subdir/file-$subdir.bin",
                "minis",
            )
            assertNotNull("$subdir must be reachable", found)
        }
    }

    @Test
    fun globalSubdirsAreNotDoubleResolved() {
        // memory/skills/shared are already global bind mounts; treating them
        // as per-session here would look for a second, non-existent copy.
        for (subdir in listOf("memory", "skills", "shared")) {
            val found = ChatLinkResolver.resolveInGlobalPool(
                globalRoot(),
                "minis://$subdir/whatever.md",
                "minis",
            )
            assertNull("$subdir must not go through the per-session fallback", found)
        }
    }

    @Test
    fun missingFileReturnsNullRatherThanGuessing() {
        assertNull(
            ChatLinkResolver.resolveInGlobalPool(
                globalRoot(),
                "minis://attachments/absent.apk",
                "minis",
            ),
        )
    }

    @Test
    fun traversalOutOfTheGlobalRootIsRefused() {
        val outside = File(tmp.root, "secret.txt").apply { writeText("nope") }
        assertTrue(outside.exists())

        // The fallback builds File(root, subdir, tail); a tail with .. must
        // not be able to read outside the per-session subdir.
        val found = ChatLinkResolver.resolveInGlobalPool(
            globalRoot(),
            "minis://attachments/../$outside.name",
            "minis",
        )
        assertNull("traversal must not resolve", found)
    }

    @Test
    fun aDirectoryIsNotAValidTarget() {
        File(globalRoot(), "offloads").mkdirs()
        assertNull(
            ChatLinkResolver.resolveInGlobalPool(
                globalRoot(),
                "minis://offloads",
                "minis",
            ),
        )
    }

    @Test
    fun nonMinisSchemesAreNotHandledHere() {
        writeInGlobalPool("attachments", "a.bin")
        assertNull(
            ChatLinkResolver.resolveInGlobalPool(
                globalRoot(),
                "https://example.com/a.bin",
                "https",
            ),
        )
    }
}