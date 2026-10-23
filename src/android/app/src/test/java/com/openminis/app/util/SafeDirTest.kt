package com.openminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Contract of [SafeDir], the replacement for `File.listFiles()` / `File.list()`.
 *
 * The platform listing kills the process on a file name that holds a
 * character outside the BMP, so these tests pin the behaviour that keeps the
 * app alive and the contract the call sites rely on.
 */
class SafeDirTest {

    private fun tmpDir(name: String): File {
        val d = File(System.getProperty("java.io.tmpdir"), "safedir-$name-${System.nanoTime()}")
        d.mkdirs()
        return d
    }

    /**
     * The name that killed the app on Android 15: Cyrillic together with a
     * character outside the BMP. `File.list()` aborts the process on it
     * because the platform hands the raw bytes to `NewStringUTF`.
     *
     * The exact round-trip is only asserted when the JVM can represent the
     * name on disk. Under a C locale `sun.jnu.encoding` is ASCII, the file is
     * created with substituted bytes and the comparison would fail for a
     * reason that has nothing to do with SafeDir. What always has to hold is
     * that the listing completes and reports the entry.
     */
    @Test
    fun listsNameWithAstralCharacter() {
        val encoding = System.getProperty("sun.jnu.encoding") ?: ""
        val name = "2_Checkout (проверка \uD83D\uDE80 api_ root-).txt"
        val dir = tmpDir("astral")
        try {
            val f = File(dir, name)
            assertTrue("precondition: file created", f.createNewFile())

            val names = SafeDir.listNames(dir)

            assertNotNull("listing must succeed, not abort", names)
            assertEquals("exactly one entry", 1, names!!.size)

            val files = SafeDir.listFiles(dir)
            assertNotNull(files)
            assertEquals(1, files!!.size)

            assumeTrue(
                "sun.jnu.encoding=$encoding cannot round-trip the name",
                encoding.lowercase().contains("utf")
            )
            assertEquals("the astral name must survive intact", name, names[0])
            assertEquals(name, files[0].name)
            assertTrue("the entry must still resolve", files[0].exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun listFilesReturnsNullForMissingDirectory() {
        val missing = File(System.getProperty("java.io.tmpdir"), "safedir-missing-${System.nanoTime()}")
        assertNull(SafeDir.listNames(missing))
        assertNull(SafeDir.listFiles(missing))
        assertNull(SafeDir.listFiles(missing) { true })
    }

    @Test
    fun listFilesReturnsNullForAFile() {
        val dir = tmpDir("notdir")
        try {
            val f = File(dir, "plain.txt")
            f.writeText("x")
            assertNull(SafeDir.listFiles(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun filterKeepsOnlyMatchingEntries() {
        val dir = tmpDir("filter")
        try {
            File(dir, "a.log").writeText("a")
            File(dir, "b.txt").writeText("b")
            File(dir, "c.log").writeText("c")
            val logs = SafeDir.listFiles(dir) { it.name.endsWith(".log") }
            assertNotNull(logs)
            assertEquals(setOf("a.log", "c.log"), logs!!.map { it.name }.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The traversal order has to match `File.walkTopDown()`: a parent is
     * immediately followed by its own children. `Sequence.maxDepth` derives
     * the depth of an element from its position, so a different order would
     * silently change what the depth limit means.
     */
    @Test
    fun walkKeepsTheStdlibPreOrder() {
        val dir = tmpDir("walk")
        try {
            val a = File(dir, "a").also { it.mkdirs() }
            val b = File(dir, "b").also { it.mkdirs() }
            File(a, "a1.txt").writeText("1")
            File(a, "a2.txt").writeText("2")
            File(b, "b1.txt").writeText("3")
            File(dir, "c.txt").writeText("4")

            val expected = dir.walkTopDown().map { it.name }.toList()
            val actual = SafeDir.walk(dir).map { it.name }.toList()

            assertEquals("order must match the stdlib walk", expected, actual)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun walkBottomUpYieldsChildrenBeforeParents() {
        val dir = tmpDir("walkbottom")
        try {
            val sub = File(dir, "sub").also { it.mkdirs() }
            File(sub, "leaf.txt").writeText("x")
            File(dir, "top.txt").writeText("y")

            val names = SafeDir.walkBottomUp(dir).map { it.name }.toList()

            assertEquals("the root must come last", dir.name, names.last())
            assertTrue("the leaf must precede its directory", names.indexOf("leaf.txt") < names.indexOf("sub"))
            assertTrue("the leaf must precede the root", names.indexOf("leaf.txt") < names.indexOf(dir.name))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A symlink cycle must terminate instead of spinning forever. */
    @Test
    fun walkTerminatesOnSymlinkCycle() {
        val dir = tmpDir("cycle")
        try {
            val sub = File(dir, "sub").also { it.mkdirs() }
            val link = File(sub, "loop")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), dir.toPath())
            } catch (_: Exception) {
                return // no symlink support on this host, nothing to prove
            }
            val seen = SafeDir.walk(dir).count()
            assertTrue("the walk must terminate", seen < 1000)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The depth limit has to be a real bound, not a hint.
     *
     * `maxDepth` is what keeps debug.appInfo and the offload size walks from
     * running to the bottom of the whole tree, and it has no other consumer, so
     * a regression here would be invisible until a device call took minutes.
     * Depth is counted the way the stdlib counts it: the root is depth 0, its
     * children depth 1.
     */
    @Test
    fun walkHonoursTheDepthLimit() {
        val dir = tmpDir("depth")
        try {
            // root/d1/d2/d3/d4/file — file sits at depth 5.
            val deepest = File(dir, "d1/d2/d3/d4").also { it.mkdirs() }
            File(deepest, "file.txt").writeText("x")

            val all = SafeDir.walk(dir, 12).toList()
            assertTrue("an unlimited depth must reach the file", all.any { it.name == "file.txt" })

            val cappedAt2 = SafeDir.walk(dir, 2).toList()
            val names = cappedAt2.map { it.relativeTo(dir).path }
            assertTrue("the root is depth 0 and must be included", names.contains(""))
            assertTrue("depth 1 must be included", names.contains("d1"))
            assertTrue("depth 2 must be included", names.contains("d1/d2"))
            assertFalse("depth 3 is past the limit", names.contains("d1/d2/d3"))
            assertFalse(
                "nothing below the limit may be yielded",
                names.any { it.count { ch -> ch == '/' } >= 2 },
            )

            // Depth 0 alone must be just the root: a limit of 0 means "do not
            // descend at all", not "one level".
            assertEquals(
                listOf(""),
                SafeDir.walk(dir, 0).map { it.relativeTo(dir).path },
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
