package com.openminis.app.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guard against the platform listing coming back.
 *
 * `File.listFiles()` and `File.list()` abort the whole process on Android 15
 * as soon as a directory holds a name with a character outside the BMP, and
 * the abort cannot be caught. [SafeDir] exists for exactly that reason, so a
 * single new call to the stdlib listing anywhere in the app would silently
 * bring the crash back.
 *
 * The check reads the sources of the app module. When they are not on disk -
 * which is the case for a packaged test run - the test is skipped rather
 * than failed, because there is nothing to inspect.
 */
class SafeDirSourceGuardTest {

    private fun moduleDir(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 6) {
            val sources = File(dir, "src/main/java")
            if (sources.isDirectory) return dir
            dir = dir.parentFile
            hops++
        }
        return null
    }

    @Test
    fun appCodeNeverUsesTheFatalPlatformListing() {
        val module = moduleDir()
        assumeTrue("app sources are not on disk, nothing to inspect", module != null)
        val sources = File(module!!, "src/main/java")
        assumeTrue(sources.isDirectory)

        val offenders = ArrayList<String>()

        fun scan(file: File) {
            val name = file.name
            if (file.isDirectory) {
                SafeDir.listFiles(file)?.forEach { scan(it) }
                return
            }
            if (!name.endsWith(".kt")) return
            if (name == "SafeDir.kt") return
            val text = try {
                file.readText()
            } catch (_: Exception) {
                return
            }
            text.lineSequence().forEachIndexed { index, line ->
                val code = line.substringBefore("//").trim()
                if (code.isEmpty()) return@forEachIndexed
                val hits = buildList {
                    if (Regex("""\.listFiles\s*\(""").containsMatchIn(code)) add("listFiles")
                    if (Regex("""\.listFiles\s*\{""").containsMatchIn(code)) add("listFiles{}")
                    if (Regex("""\.list\s*\(\s*\)""").containsMatchIn(code)) add("list")
                    if (code.contains("walkTopDown(")) add("walkTopDown")
                    if (code.contains("walkBottomUp(")) add("walkBottomUp")
                }
                if (hits.isNotEmpty()) {
                    offenders.add(
                        "${file.relativeTo(sources).path}:${index + 1} uses ${hits.joinToString(",")} - ${code.take(90)}"
                    )
                }
            }
        }

        scan(sources)

        assertTrue(
            "The platform listing aborts the process on a file name with a character " +
                "outside the BMP. Use SafeDir.listFiles / SafeDir.listNames / SafeDir.walk " +
                "instead.\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
