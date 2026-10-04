package com.openminis.app.sandbox

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * [T-embedded-search] ripgrep as a bundled native binary (ZCode port —
 * their dependencies/native-search pattern: vendor a musl-static build,
 * extract once, exec directly).
 *
 * WHY musl-static: the binary has zero libc linkage — it runs on Android's
 * bionic unmodified. WHY direct exec (not through the PRoot shell):
 * a search call skips the proot+pty roundtrip entirely (~50-150ms saved
 * per call) and ripgrep's parallel scanner handles large trees far faster
 * than BusyBox grep.
 *
 * Lifecycle: the asset is copied to filesDir/native/rg on first use and
 * chmod +x'd; the copy is verified once per process by a version probe.
 * Path space: callers resolve MODEL paths (guest /var/minis/...) to HOST
 * paths via [PRootKernel.resolveSessionHostPath] before scanning — the
 * binary sees the same files the agent addresses.
 */
object NativeSearch {

    private const val ASSET_PATH = "native/rg"
    private const val OUTPUT_CAP_BYTES = 400 * 1024
    private const val MIN_BINARY_BYTES = 1_000_000

    @Volatile
    private var verified = false

    /** Executable rg for this process, extracting on first use. */
    fun ensureBinary(context: Context): File {
        val out = File(context.filesDir, "native/rg")
        if (out.exists() && out.length() >= MIN_BINARY_BYTES) return out
        synchronized(this) {
            if (out.exists() && out.length() >= MIN_BINARY_BYTES) return out
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".tmp")
            context.assets.open(ASSET_PATH).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(out)) {
                // A concurrent extractor won the race — tmp is
                // byte-identical, drop ours.
                tmp.delete()
            }
            out.setExecutable(true, false)
            out.setReadable(true, false)
        }
        return out
    }

    /**
     * Run rg with argv (paths already HOST-space). NOT through a shell:
     * ProcessBuilder + raw argv = zero quoting surface. Exit codes: 0
     * matches, 1 no matches, 2+ error. Output capped hard.
     */
    fun exec(
        context: Context,
        argv: List<String>,
        timeoutSec: Int = 30,
    ): Triple<String, Int> {
        val bin = ensureBinary(context)
        if (!verified) {
            val probe = runRg(bin, listOf("--version"), 5)
            if (!probe.first.startsWith("ripgrep")) {
                return Triple(
                    "native rg failed self-check: ${probe.first.take(120)}", -1)
            }
            verified = true
        }
        return runRg(bin, argv, timeoutSec)
    }

    private fun runRg(bin: File, argv: List<String>, timeoutSec: Int): Triple<String, Int> {
        val cmd = listOf(bin.absolutePath) + argv
        return runCatching {
            val proc = ProcessBuilder(cmd).start()
            val sb = StringBuilder()
            var bytes = 0
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (bytes < OUTPUT_CAP_BYTES) {
                        sb.append(line).append('\n')
                        bytes += line.length + 1
                    }
                }
            }
            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runCatching Triple(
                    sb.toString() +
                        "\n[ripgrep timed out after ${timeoutSec}s — narrow the path or pattern]",
                    124)
            }
            val err = runCatching {
                proc.errorStream.bufferedReader().readText().take(300)
            }.getOrDefault("")
            val code = proc.exitValue()
            val capped = if (bytes >= OUTPUT_CAP_BYTES) {
                sb.toString() + "\n…(output truncated at 400KB)"
            } else sb.toString()
            val out = if (code > 1 && err.isNotBlank()) {
                "$capped\n[rg stderr] $err"
            } else capped
            Triple(out, code)
        }.getOrElse { Triple("rg exec failed: ${it.message?.take(200)}", -1) }
    }
}
