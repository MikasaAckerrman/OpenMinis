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

    /**
     * Call sites that look like the fatal listing but are not.
     *
     * `ScheduledTaskOffloadHandler` calls `manager.list()` on its own
     * `TaskManager`, which has nothing to do with `java.io.File`.
     *
     * `SafeDirTest` is exempt as a whole: it has to call `walkTopDown()` in
     * order to prove SafeDir reproduces the stdlib's order. It only ever
     * applies it to a temporary directory it filled with plain ASCII names,
     * so there is no file name for the platform listing to choke on.
     */
    private val allowed = listOf(
        "manager.list()" to "TaskManager.list(), not java.io.File"
    )

    private val exemptFiles = setOf("SafeDir.kt", "SafeDirTest.kt")

    /**
     * No source tree may use the platform listing.
     *
     * `src/androidTest` is included even though CI never executes those
     * tests - there is no emulator job in any workflow - because a test that
     * is never run is exactly where a dangerous call would rot unnoticed, and
     * the file it uses still aborts the process the day somebody runs it on a
     * device.
     */
    @Test
    fun appCodeNeverUsesTheFatalPlatformListing() {
        val module = moduleDir()
        assumeTrue("app sources are not on disk, nothing to inspect", module != null)

        val roots = listOf("src/main/java", "src/androidTest/java", "src/test/java")
            .map { File(module!!, it) }
            .filter { it.isDirectory }

        assumeTrue("no Kotlin source trees found", roots.isNotEmpty())

        val offenders = ArrayList<String>()

        fun scan(file: File, sources: File) {
            val name = file.name
            if (file.isDirectory) {
                SafeDir.listFiles(file)?.forEach { scan(it, sources) }
                return
            }
            if (!name.endsWith(".kt")) return
            if (name in exemptFiles) return
            val text = try {
                file.readText()
            } catch (_: Exception) {
                return
            }
            // Documentation is stripped first: a KDoc line that explains why
            // the stdlib listing is fatal must not be reported as a call.
            var inBlockComment = false
            val rawLines = text.lineSequence().toList()
            rawLines.forEachIndexed { index, rawLine ->
                val trimmed = rawLine.trimStart()
                if (inBlockComment) {
                    if (trimmed.contains("*/")) inBlockComment = false
                    return@forEachIndexed
                }
                if (trimmed.startsWith("/*")) {
                    if (!trimmed.contains("*/")) inBlockComment = true
                    return@forEachIndexed
                }
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    return@forEachIndexed
                }
                val code = rawLine.substringBefore("//").trim()
                if (code.isEmpty()) return@forEachIndexed
                val hits = buildList {
                    // The lookbehind matters: without it the guard would flag
                    // every SafeDir call, which is exactly the code that fixes
                    // the crash.
                    if (Regex("""(?<!SafeDir)\.listFiles\s*\(""").containsMatchIn(code)) add("listFiles")
                    if (Regex("""(?<!SafeDir)\.listFiles\s*\{""").containsMatchIn(code)) add("listFiles{}")
                    if (Regex("""(?<!SafeDir)\.list\s*\(\s*\)""").containsMatchIn(code)) add("list")
                    if (Regex("""(?<!SafeDir)\.walkTopDown\s*\(""").containsMatchIn(code)) add("walkTopDown")
                    if (Regex("""(?<!SafeDir)\.walkBottomUp\s*\(""").containsMatchIn(code)) add("walkBottomUp")
                }
                if (hits.isEmpty()) return@forEachIndexed
                val exempt = allowed.firstOrNull { (needle, _) -> code.contains(needle) }
                if (exempt != null) return@forEachIndexed
                offenders.add(
                    "${file.relativeTo(sources).path}:${index + 1} uses ${hits.joinToString(",")} - ${code.take(90)}"
                )
            }
        }

        for (root in roots) scan(root, root)

        assertTrue(
            "The platform listing aborts the process on a file name with a character " +
                "outside the BMP. Use SafeDir.listFiles / SafeDir.listNames / SafeDir.walk " +
                "instead.\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
