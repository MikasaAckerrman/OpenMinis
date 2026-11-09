package com.openminis.app.sandbox

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [T-embedded-search] Native ripgrep execution for the agent's grep/glob
 * tools. The binary ships as `jniLibs/arm64-v8a/librg.so` — the package
 * installer extracts it into `nativeLibraryDir`, the ONLY app-writable
 * location Android's SELinux allows exec() from on API 29+ (same pattern
 * as our proot: RootfsManager.libproot). Executing from filesDir was
 * EACCES (live-tested on device, vc83 self-check).
 */
object NativeSearch {

    /** First-version output we accept (any "ripgrep <ver>" prefix). */
    private const val VERSION_PREFIX = "ripgrep"

    /** Output cap — matches the tool-result conventions of other tools. */
    private const val MAX_OUTPUT_BYTES = 400_000

    @Volatile
    private var verified = false

    /**
     * The installed binary; null (with an error message) if the package
     * is broken or the ABI is wrong — callers degrade to their error text.
     */
    fun binary(context: Context): Pair<File?, String> {
        val bin = File(context.applicationInfo.nativeLibraryDir, "librg.so")
        if (!bin.exists() || bin.length() < 1_000_000) {
            return null to "native rg binary missing from nativeLibraryDir " +
                "(broken install or wrong ABI)"
        }
        return bin to ""
    }

    /** One-per-process version probe; sets [verified] on success. */
    private fun selfCheck(bin: File): Pair<Boolean, String> {
        if (verified) return true to ""
        val probe = runRg(bin, listOf("--version"), 5)
        val ok = probe.second == 0 && probe.first.startsWith(VERSION_PREFIX)
        if (ok) verified = true
        return ok to if (ok) "" else
            "native rg failed self-check: ${probe.first.take(120)}"
    }

    /**
     * Run rg with [argv]; returns (stdout, exitCode). Exit 1 = no matches
     * (caller decides semantics); negative = infra failure (timeout/exec).
     */
    fun exec(context: Context, argv: List<String>, timeoutSec: Int = 30): Pair<String, Int> {
        val (bin, err) = binary(context)
        if (bin == null) return err to -1
        val (ok, checkErr) = selfCheck(bin)
        if (!ok) return checkErr to -1
        return runRg(bin, argv, timeoutSec)
    }

    private fun runRg(bin: File, argv: List<String>, timeoutSec: Int): Pair<String, Int> {
        return runCatching {
            val cmd = listOf(bin.absolutePath) + argv
            val proc = ProcessBuilder(cmd)
                // [B45-deadlock] stderr MUST be merged into stdout: a
                // separate stderr pipe nobody drains fills at 64 KiB and
                // the child blocks on write() forever while the parent
                // blocks in readBytes() — the classic ProcessBuilder
                // deadlock (vc157 live report: 4 tools frozen holding
                // their scheduler permits, the whole round stalls). A
                // bonus: rg's own error text now reaches the model
                // instead of vanishing into an undrained pipe.
                .redirectErrorStream(true)
                .start()
            try {
                // [B45-deadlock] Drain on a side thread: readBytes()
                // blocks until EOF and EOF never comes from a blocked
                // child, so waitFor() below would never be reached. The
                // pattern is TelegramLoginRunner's: side-thread drain +
                // bounded waitFor + destroyForcibly backstop.
                var out = ""
                val reader = kotlin.concurrent.thread(name = "rg-out", isDaemon = true) {
                    try {
                        out = proc.inputStream.bufferedReader().readText()
                    } catch (_: Exception) {
                        // Stream torn down by the timeout path — nothing to do.
                    }
                }
                val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    reader.join(2000)
                    return@runCatching "(rg timed out after ${timeoutSec}s — pattern too " +
                        "expensive or tree too large; narrow it)" to -1
                }
                reader.join(2000)
                // [T-output-cap] Truncate to the tool budget before returning.
                val text = if (out.length > MAX_OUTPUT_BYTES) {
                    out.take(MAX_OUTPUT_BYTES) + "\n…(rg output truncated at 400KB)"
                } else {
                    out
                }
                text to proc.exitValue()
            } finally {
                proc.destroyForcibly()
            }
        }.getOrElse { Pair("rg exec failed: ${it.message?.take(200)}", -1) }
    }
}
