package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val TAG = "PersistentShell"
        private const val STDERR_HEAD_LINES = 25
        private const val STDERR_TAIL_LINES = 15
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    /**
     * Why the last [startProcess] attempt produced no usable shell.
     *
     * [T-clone-variant] Without this the only symptom of a dead sandbox was the
     * bare string "[Shell not running]" in the chat — proot's own explanation
     * went to logcat (stderr is NOT merged into stdout in debug builds, see
     * redirectErrorStream below), which a user on-device cannot read. Every
     * failure mode is now surfaced in-band so the reason is pasteable.
     */
    @Volatile
    private var startFailure: String? = null

    /**
     * What proot printed, kept so a start failure can quote it.
     *
     * Two buffers, not one. proot calls talloc_enable_leak_report() (cli.c), so
     * on exit it dumps a multi-hundred-line talloc hierarchy to stderr. A plain
     * "keep the last N lines" buffer therefore throws away the actual error and
     * shows only leak-report tail — which is exactly what happened on the first
     * clone build: `exit=1` plus 40 lines of "HandlerEntry contains 30 bytes".
     *
     * [head] keeps the FIRST lines, where proot's own complaint appears before
     * the report starts. [tail] keeps the last lines for cases where the failure
     * comes late. Recognisable leak-report noise is dropped on the way in.
     */
    private val stderrHead = ArrayList<String>(STDERR_HEAD_LINES)
    private val stderrTail = java.util.ArrayDeque<String>()

    /**
     * True for lines that belong to proot's talloc leak report rather than to
     * any error. Matching on shape, not on an exact string: the report prints
     * "<name> contains N bytes in M blocks (ref 0) 0x..." plus a
     * "talloc report on '<ctx>'" header.
     */
    private fun isTallocNoise(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("talloc report on ") ||
            t.startsWith("full talloc report on ") ||
            (t.contains(" contains ") && t.contains(" bytes in ") && t.contains(" blocks"))
    }

    private fun recordStderr(line: String) {
        if (isTallocNoise(line)) return
        synchronized(stderrHead) {
            if (stderrHead.size < STDERR_HEAD_LINES) stderrHead.add(line)
        }
        synchronized(stderrTail) {
            stderrTail.addLast(line)
            while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
        }
    }

    /**
     * The captured output, head first, with the middle elided if both buffers
     * filled up. Bounded so the result stays pasteable into a chat message.
     */
    private fun stderrTailText(): String {
        val head = synchronized(stderrHead) { stderrHead.toList() }
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        if (head.isEmpty() && tail.isEmpty()) return ""
        // Tail already repeats head when little was printed — the common case.
        if (tail.size <= STDERR_TAIL_LINES && head.containsAll(tail)) {
            return head.joinToString("\n")
        }
        val overlap = tail.filterNot { head.contains(it) }
        return buildString {
            append(head.joinToString("\n"))
            if (overlap.isNotEmpty()) {
                append("\n  ...\n")
                append(overlap.joinToString("\n"))
            }
        }
    }

    /**
     * The in-band diagnostic shown where "[Shell not running]" used to be.
     *
     * Keeps the original marker as the first line so existing log greps and any
     * UI that matched on it still work, then appends the concrete reason.
     */
    private fun shellNotRunningMessage(): String {
        val reason = startFailure
        return if (reason.isNullOrEmpty()) {
            "[Shell not running] no start attempt recorded — PRoot kernel may not have booted"
        } else {
            "[Shell not running] $reason"
        }
    }

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** [diag] Read back the mount this shell was started with (frozen at boot). */
    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
    )

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) { startProcess() }
        } finally {
            isStarting.set(false)
        }
    }

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")
        startFailure = null

        val rootfsManager = RootfsManager.getInstance(context)

        // [T-clone-variant] Preflight the two files the shell cannot run
        // without. Both are per-install: a fresh install (e.g. the clone
        // variant) has its own empty filesDir and its own nativeLibraryDir, so
        // "works on the primary install" says nothing about this one. Failing
        // here with a named cause beats ProcessBuilder throwing ENOENT that
        // nobody sees.
        if (!rootfsManager.prootBinary.exists()) {
            startFailure = "proot binary missing at ${rootfsManager.prootBinary.absolutePath} " +
                "(APK built without jniLibs/arm64-v8a/libproot.so?)"
            Log.e(TAG, startFailure!!)
            AppLogger.error(TAG, startFailure!!)
            return
        }
        if (!rootfsManager.isInstalled) {
            startFailure = "Alpine rootfs not installed at ${rootfsManager.rootfsDir.absolutePath} " +
                "(extraction never ran or failed) — open the app's onboarding / " +
                "Settings → Rootfs to reinstall"
            Log.e(TAG, startFailure!!)
            AppLogger.error(TAG, startFailure!!)
            return
        }

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEBUG

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        val p = try {
            processBuilder.start()
        } catch (e: Exception) {
            // ENOENT / EACCES on the proot binary, fork failure under memory
            // pressure, SELinux denial — all land here and used to vanish.
            startFailure = "failed to spawn proot: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, startFailure!!, e)
            AppLogger.error(TAG, startFailure!!)
            return
        }
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat AND into a bounded
        // ring buffer so a start failure can be explained in the chat itself.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) {
                        Log.d("PRootStderr", line)
                        recordStderr(line)
                    }
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        // proot commonly starts and then dies immediately (bad -r root, missing
        // loader, unusable /proc). Catch that here so the exit code and proot's
        // own complaint reach the caller instead of a bare "not running".
        if (!p.isAlive) {
            val code = try { p.exitValue() } catch (_: IllegalThreadStateException) { null }
            // Give the drain threads a moment to flush what proot printed on its
            // way out — otherwise the tail is often empty and the diagnostic
            // loses the only line that matters.
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            val tail = stderrTailText()
            val detail = if (tail.isNotEmpty()) {
                "\nproot output:\n$tail"
            } else {
                " — no output captured"
            }
            startFailure = "proot exited immediately (exit=$code)$detail"
            Log.e(TAG, startFailure!!)
            // Also into the daily log file: Log.e only reaches logcat, and the
            // whole point of this diagnostic is that the device user cannot read
            // logcat. AppLogger.error writes the file when logging is enabled.
            AppLogger.error(TAG, startFailure!!)
            return
        }

        Log.i(TAG, "Persistent shell started")
    }

    private fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n, StandardCharsets.UTF_8)

                val cb = pendingCallback
                if (cb != null) {
                    // Check if this chunk contains the end marker
                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = text.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        // Extract output before marker
                        val beforeMarker = text.substring(0, markerIdx)
                        cb.output.append(beforeMarker)
                        if (cb.lineCallback != null) {
                            feedLines(beforeMarker, cb.lineCallback)
                        }

                        // Extract exit code from marker line
                        val afterMarker = text.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker)

                        // Signal completion
                        cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        pendingCallback = null
                    } else {
                        cb.output.append(text)
                        if (cb.lineCallback != null) {
                            feedLines(text, cb.lineCallback)
                        }
                    }
                }
                // If no pending callback, this is shell prompt noise — or, in
                // release builds (redirectErrorStream=true), proot's own error
                // output on a failed boot. Keep it in the ring buffer so a
                // start failure can quote it; it is discarded otherwise.
                if (cb == null) {
                    for (line in text.split('\n')) {
                        if (line.isNotBlank()) recordStderr(line.trimEnd('\r'))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited
        val cb = pendingCallback
        if (cb != null) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
            pendingCallback = null
        }

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

    private fun parseExitCode(text: String, marker: String): Int {
        // Pattern: __MINIS_DONE_{marker}_EXIT_{code}__
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            return Pair(shellNotRunningMessage(), -1)
        }

        val marker = UUID.randomUUID().toString().take(8)
        val wrappedCommand = "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode ->
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        pendingCallback = null
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout — cancel pending, but don't kill the shell
                pendingCallback = null
                Pair("[Command timed out after ${timeout / 1000}s]", 124)
            } else {
                result
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!isAlive) return
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                for (key in previousKeys - envVars.keys) {
                    writer.write("unset $key\n")
                }
                for ((key, value) in envVars) {
                    // Escape single quotes in values
                    val escaped = value.replace("'", "'\\''")
                    writer.write("export $key='$escaped'\n")
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /**
     * Stop the persistent shell.
     */
    fun stop() {
        try { stdinWriter?.close() } catch (_: Exception) {}
        stdinWriter = null
        process?.destroyForcibly()
        process = null
        pendingCallback?.let {
            it.onComplete?.invoke(it.output.toString(), -1)
        }
        pendingCallback = null
        Log.i(TAG, "Persistent shell stopped")
    }
}
