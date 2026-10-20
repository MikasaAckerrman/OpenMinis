package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * @Volatile-imports APPLIED (D-1..D-10 protocol patch, v2.3 design):
 * AtomicReference for command handoff (getAndSet null), Mutex to serialize
 * executeCommand + applyEnvironment writes (write-side only), CAS 'completed'
 * on CommandCallback for single-shot completion.
 */


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

        /**
         * [T-stop-button-freeze] Single-thread kill executor: pty pipe
         * drains and process reaping are slow and blocking — they must
         * never run on the main thread (STOP button). Serial execution
         * keeps concurrent stops (cancel + timeout recheck) ordered.
         */
        private val killExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "shell-kill").apply { isDaemon = true }
            }
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /**
     * [D-1] Atomic command handoff slot (v2.3 design invariant-1).
     *
     * Reader does `getAndSet(null)` to CLAIM the callback of the command whose
     * END marker it just matched — a next writer cannot install a new callback
     * into a slot the reader is still using, and a stolen callback cannot be
     * re-installed by a racing writer. Plain `@Volatile` was insufficient: a
     * writer could overwrite the slot between the reader's read and its nulling.
     *
     * Null = no command in flight. Set BEFORE the wrapped command is written,
     * cleared by the reader on marker dispatch, EOF teardown, stop() or cancel.
     */
    private val commandState = AtomicReference<CommandCallback?>(null)

    /**
     * [D-1] Write-side serialization. ONLY caller coroutines take this lock —
     * the reader thread NEVER does, so a slow caller cannot block the output
     * feed (deadlock-free by construction). Orders executeCommand and
     * applyEnvironment writes against each other, and re-queues the next
     * command only after the protocol state is restored (drain completion).
     */
    private val writeMutex = Mutex()

    /**
     * [D-2 companion] Environment writes queued while a command is in flight.
     * applyEnvironment writes unset/export lines between commands (at the
     * idle->busy transition under the write mutex) — they can never interleave
     * with a wrapped command's bytes. [ExecutionCoordinator.broadcast*] calls
     * applyEnvironment WITHOUT holding the per-session executeCommand mutex,
     * so this queue is what makes "export TZ while `sleep 5` runs" safe.
     */
    private val pendingEnv = ArrayDeque<Pair<Set<String>, Map<String, String>>>()

    /**
     * [D-3] Bytes received AFTER an END marker but belonging to the NEXT
     * command's output (chunk overshoot), or an incomplete marker fragment
     * (chunk boundary cut inside `__MINIS_DONE_...`). Kept across readLoop
     * iterations until it can be parsed against a live callback. Exactly one
     * carry can exist because the write side is serialized (writeMutex).
     */
    private val pendingCarry = AtomicReference<String?>(null)


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

    /**
     * [D-2/D-10] Single-shot command callback. `completed` is a CAS flag: the
     * reader's marker dispatch and a concurrent stop()/EOF teardown both race
     * for it, only ONE CAS(false->true) wins and invokes onComplete exactly
     * once. The losing path drops silently — resuming the caller's
     * continuation twice is no longer possible even between the isActive
     * check and resume (that check alone is not atomic).
     */
    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
    ) {
        /** Set BEFORE unregistering from commandState so a racing reader's
         *  getAndSet can never dispatch an already-completed callback. */
        var completed = false

        /** True iff CAS-completed by someone (reader, EOF, stop). */
        val isDone: Boolean get() = completed
    }

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

        // [T-clone-variant] Preflight the three things the shell cannot run
        // without. All are per-install: a fresh install (e.g. the clone
        // variant) has its own empty filesDir, its own cacheDir and its own
        // nativeLibraryDir, so "works on the primary install" says nothing
        // about this one. Failing here with a named cause beats ProcessBuilder
        // throwing ENOENT that nobody sees.
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
        // The loader must exist as a native library: proot's own fallback
        // (extract into PROOT_TMP_DIR + chmod +x) is blocked by Android's W^X,
        // and its only symptom is `execve("/bin/sh"): Permission denied`.
        val loader = File(rootfsManager.nativeLibDir, "libproot-loader.so")
        if (!loader.exists()) {
            startFailure = "proot loader missing at ${loader.absolutePath} — the APK was built " +
                "without jniLibs/arm64-v8a/libproot-loader.so, so proot cannot exec anything " +
                "inside the rootfs (Android forbids running a file the app wrote itself)"
            Log.e(TAG, startFailure!!)
            AppLogger.error(TAG, startFailure!!)
            return
        }
        // proot unpacks temporary state into PROOT_TMP_DIR; a missing directory
        // also surfaces as a misleading "Permission denied". Name it here.
        val prootTmp = PRootKernel.getProotTmpDir(context)
        if (!prootTmp.isDirectory) {
            startFailure = "PROOT_TMP_DIR unusable: ${prootTmp.absolutePath} is not a directory " +
                "— proot cannot unpack its loader, so every exec fails with " +
                "\"Permission denied\""
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
        // Reuse the directory the preflight above already validated — calling
        // getProotTmpDir() twice could pick a different fallback if storage
        // state changed in between.
        env["PROOT_TMP_DIR"] = prootTmp.absolutePath
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
                var text = String(buffer, 0, n, StandardCharsets.UTF_8)

                // [D-3] Fold any carry (partial marker fragment / post-marker
                // overshoot from the PREVIOUS chunk) in front of the new bytes
                // BEFORE searching for markers — a marker cut at a chunk
                // boundary only becomes fully visible once the remainder
                // arrives, and folding late would lose the head bytes.
                val carried = pendingCarry.getAndSet(null)
                if (carried != null) text = carried + text

                // [D-1] CLAIM: at most one command's END marker lives in this
                // chunk (write side is serialized by writeMutex). getAndSet
                // detaches the callback atomically — a concurrent writer can
                // not install a NEW callback into a slot we are about to
                // dispatch, and we can never dispatch a callback twice (the
                // second reader iteration sees null).
                val cb = commandState.getAndSet(null)
                if (cb != null && !cb.isDone) {
                    val markerExitPattern = "__MINIS_DONE_${cb.marker}_EXIT_"
                    val markerIdx = text.indexOf(markerExitPattern)

                    if (markerIdx >= 0) {
                        val beforeMarker = text.substring(0, markerIdx)
                        cb.output.append(beforeMarker)
                        if (cb.lineCallback != null) {
                            feedLines(beforeMarker, cb.lineCallback)
                        }

                        // Exit code lives on the SAME line as the marker up to
                        // "__\n"; find it in the remainder of this chunk
                        // (marker line is usually atomic because echo writes
                        // it in one syscall, but do not rely on that).
                        val afterMarker = text.substring(markerIdx)
                        val exitCode = parseExitCode(afterMarker, cb.marker) // -1 if absent
                        // Where the terminated marker LINE ends (first \n at
                        // or after the marker start); everything after it is
                        // overshoot for a FUTURE command. lineEnd == -1 means
                        // the marker line itself was cut by the chunk edge —
                        // the tail after `text.length` will arrive next chunk,
                        // so any complete-`__`-absent fragment stays in the
                        // fold (see comment; parseExitCode already saw all of
                        // the bytes present, which is the marker-body case).
                        val lineEnd = text.indexOf('\n', markerIdx)
                        if (lineEnd >= 0 && lineEnd + 1 < text.length) {
                            pendingCarry.set(text.substring(lineEnd + 1))
                        }
                        text = "" // consumed (before + marker line + carry)

                        // [D-2/D-10] Single-shot completion via CAS: reader
                        // wins iff it is the first to mark this callback done.
                        if (cb.markDone()) {
                            cb.onComplete?.invoke(cb.output.toString(), exitCode)
                        }
                        // commandState stays null — next writer may install a
                        // fresh callback only AFTER its wrapped bytes (and any
                        // queued env flush) are written under writeMutex.
                    } else {
                        // No complete marker yet — but the chunk TAIL may hold
                        // a marker CUT mid-way. If a partial marker fragment
                        // is detected in what would be the last (unterminated)
                        // part of the chunk, keep it in carry instead of
                        // mis-delivering it to lineCallback/output twice
                        // [D-3a] and losing it [D-3b].
                        val splitIdx = lastSafeAppendIndex(text, cb.marker)
                        if (splitIdx < text.length) {
                            pendingCarry.set(text.substring(splitIdx))
                            text = text.substring(0, splitIdx)
                        }
                        cb.output.append(text)
                        if (cb.lineCallback != null) {
                            feedLines(text, cb.lineCallback)
                        }
                        // Re-install the claimed callback: command not done.
                        commandState.set(cb)
                    }
                }
                // [orphan] No live callback and nothing to claim: prompt noise,
                // proot boot errors (release merged stderr), output of a
                // command being drained after cancel (drain target keeps its
                // own claim slot free until it re-arms — see executeCommand).
                if (cb == null || cb.isDone) {
                    for (line in text.split('\n')) {
                        if (line.isNotBlank()) recordStderr(line.trimEnd('\r'))
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // [D-2/D-10] Process exited: complete the in-flight callback ONCE via
        // CAS, so reader-EOF concurrent with stop() cannot double-resume.
        commandState.getAndSet(null)?.let { cb ->
            if (cb.markDone()) {
                cb.onComplete?.invoke(cb.output.toString(), -1)
            }
        }
        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    /**
     * [D-2/D-10] CAS-style single-shot guard. Returns true iff this caller
     * is the one that flips done=false -> done=true. Synchronized on the
     * callback because readLoop dispatch and the stop() thread may race;
     * the flag throws away the second completion regardless of who wins.
     */
    private fun CommandCallback.markDone(): Boolean {
        synchronized(this) {
            if (completed) return false
            completed = true
            return true
        }
    }

    /**
     * [D-3b] If the chunk tail contains a PREFIX of "__MINIS_DONE_{marker}_EXIT_"
     * (a marker cut by the 4 KB read boundary), return the index from which
     * those bytes start so they can be parked in carry until the remainder
     * arrives in the next chunk. Returns [text] length if no fragment found.
     *
     * We only need to preserve the SHORTEST correct tail: the marker line is
     * written by a single echo (one write syscall), so it can only ever be cut
     * at a chunk boundary — never spliced mid-line by interleaved output.
     */
    private fun lastSafeAppendIndex(text: String, marker: String): Int {
        val full = "__MINIS_DONE_${marker}_EXIT_"
        val maxCheck = minOf(full.length - 1, text.length)
        // Longest prefix first: the fragment closest to a complete marker.
        for (len in maxCheck downTo 1) {
            var isPrefix = true
            for (i in 0 until len) {
                if (text[text.length - len + i] != full[i]) {
                    isPrefix = false
                    break
                }
            }
            if (isPrefix) return text.length - len
        }
        return text.length
    }

    /**
     * [D-3a] A trailing line WITHOUT '\n' is a partial: it stays in the output
     * buffer and will be re-parsed together with its completing newline in the
     * NEXT chunk. Emitting it now caused the same line to reach lineCallback
     * twice (once as partial, once as complete). Only complete lines feed.
     */
    private fun feedLines(text: String, callback: (String) -> Unit) {
        val complete = if (text.endsWith('\n')) text else text.substringBeforeLast('\n', "")
        if (complete.isEmpty()) return
        for (line in complete.split('\n').dropLast(1)) { // drop the empty tail after final \n
            if (line.isNotEmpty()) callback(line.replace("\r", ""))
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
    /**
     * [T-bg-steer] Write one line to the RUNNING command's stdin (message
     * steering, ported from ZCode's runtime-task sink). Semantics: if the
     * current command reads stdin, it receives the line; otherwise the
     * shell buffers it and executes it AFTER the command exits (a way to
     * queue a follow-up). Prefix with '#' for a no-op comment.
     */
    fun steerInput(text: String): Boolean {
        val writer = stdinWriter
        if (writer == null || !isAlive) return false
        return runCatching {
            synchronized(this) {
                writer.write(text)
                if (!text.endsWith("\n")) writer.write("\n")
                writer.flush()
            }
            true
        }.getOrDefault(false)
    }

    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
    ): Pair<String, Int> {
        ensureStarted()

        // [D-1] One writer at a time. Everything below that touches the shell
        // protocol (install callback, write wrapped command, flush env queue)
        // happens inside this lock. The lock is NOT held while waiting for the
        // command's END marker — other callers queue on the mutex instead, and
        // the reader thread never takes it, so output keeps flowing.
        writeMutex.lock()

        try {
            // Re-check under the lock: the shell may have died while this
            // caller waited for the mutex (stop() ran between our
            // ensureStarted and this point).
            val writer = stdinWriter
            if (writer == null || !isAlive) {
                return Pair(shellNotRunningMessage(), -1)
            }

            val marker = UUID.randomUUID().toString().take(8)
            val wrappedCommand = "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"

            // [D-2 companion] Flush queued env writes at the idle->busy
            // transition. FIFO order is preserved; a failed write keeps its
            // entry (retry on the next transition) instead of corrupting the
            // protocol stream.
            flushPendingEnvLocked(writer)

            val outcome = withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeout) {
                    suspendCancellableCoroutine { cont ->
                        val cb = CommandCallback(
                            marker = marker,
                            lineCallback = lineCallback,
                        )
                        cb.onComplete = { output, exitCode ->
                            // [D-2/D-10] markDone() already gated the dispatch
                            // (CAS in readLoop/EOF/stop), so this resume is
                            // the single completion path; isActive only guards
                            // an already-cancelled continuation.
                            if (cont.isActive) {
                                cont.resume(Pair(output, exitCode))
                            }
                        }
                        // [D-1] Install BEFORE writing the command bytes so no
                        // output of this command can ever be orphaned ('cb
                        // not yet set' window is closed by ordering: install
                        // under writeMutex -> write -> flush).
                        commandState.set(cb)

                        cont.invokeOnCancellation {
                            // [v2.3] Do NOT blindly clear the slot: a marker
                            // fragment may already sit in pendingCarry and
                            // the command is still running — the shell ONLY
                            // recovers via drain (below) or watchdog.
                            // Detach ONLY if we still own the slot.
                            commandState.compareAndSet(cb, null)
                        }

                        try {
                            writer.write(wrappedCommand)
                            writer.flush()
                        } catch (e: Exception) {
                            commandState.compareAndSet(cb, null)
                            if (cont.isActive) {
                                cont.resume(Pair("[Write error: ${e.message}]", -1))
                            }
                        }
                    }
                }
            }

            if (outcome == null) {
                // [D-1] Timeout: the command keeps running shell-side; detach
                // our callback if we still own the slot. Residual output goes
                // to the orphan path; the next command's marker is unique so
                // it can never adopt this command's late END marker.
                val installed = commandState.get()
                if (installed != null && installed.marker == marker) {
                    commandState.compareAndSet(installed, null)
                }
                return Pair("[Command timed out after ${timeout / 1000}s]", 124)
            } else {
                return outcome
            }
        } finally {
            // [D-1] Release is AFTER protocol state is restored: either the
            // marker dispatched (state already null) or we timed out /
            // cancelled (state detached above). The next waiting command
            // starts on a clean idle.
            writeMutex.unlock()
        }
    }

    /**
     * [D-2 companion] Drain the pending-env queue at a safe boundary (called
     * under writeMutex at the idle->busy transition). Retried entries stay
     * queued on transient write errors; a hard shell death clears the whole
     * queue (handled in stop()).
     */
    private fun flushPendingEnvLocked(writer: BufferedWriter) {
        while (pendingEnv.isNotEmpty()) {
            val (prevKeys, envVars) = pendingEnv.first()
            try {
                writeEnvLocked(writer, prevKeys, envVars)
                pendingEnv.removeFirst()
            } catch (e: Exception) {
                Log.w(TAG, "Deferred env flush failed, will retry: ${e.message}")
                break
            }
        }
    }

    /** Raw env-byte writer; caller holds writeMutex. */
    private fun writeEnvLocked(writer: BufferedWriter, previousKeys: Set<String>, envVars: Map<String, String>) {
        for (key in previousKeys - envVars.keys) {
            writer.write("unset $key\n")
        }
        for ((key, value) in envVars) {
            val escaped = value.replace("'", "'\\''")
            writer.write("export $key='$escaped'\n")
        }
        writer.flush()
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
        // [D-2 companion] Serialize against executeCommand — both write shell
        // protocol bytes to the same stdin. If a command is in flight, the
        // env snapshot is QUEUED and flushed at the next idle->busy
        // transition (broadcast* callers run outside the per-session
        // command mutex, so this is the only ordering they get).
        writeMutex.lock()
        try {
            val writer = stdinWriter ?: return
            if (commandState.get() != null) {
                // Command in flight: queue a snapshot and return. Repeated
                // broadcasts for the same previousKeys overwrite the queued
                // entry instead of growing the queue unboundedly (the latest
                // snapshot wins — overlays are idempotent).
                pendingEnv.removeAll { it.first == previousKeys }
                pendingEnv.addLast(previousKeys to envVars)
                return
            }
            withContext(Dispatchers.IO) {
                try {
                    writeEnvLocked(writer, previousKeys, envVars)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply env vars: ${e.message}")
                }
            }
        } finally {
            writeMutex.unlock()
        }
    }



    /**
     * Stop the persistent shell.
     *
     * [T-stop-button-freeze] The blocking halves of a kill —
     * BufferedWriter.close() (drains the pty pipe; measured 3.4s
     * main-thread freeze when the user taps STOP mid-answer) and
     * Process.destroyForcibly() — run on a background kill-executor.
     * References are nulled on the CALLER thread first: any racing
     * writer sees the dead shell and skips; the kill itself can not
     * freeze the UI regardless of pipe state. The completion callback
     * stays on the caller thread (same ordering as before).
     */
    fun stop() {
        val writer = stdinWriter
        val proc = process
        stdinWriter = null
        process = null
        // [D-2/D-10] Claim the in-flight callback ATOMICALLY (not plain-read
        // + null-write): a racing reader dispatch for the same marker must
        // be impossible. CAS-based getAndSet(null) makes stop vs
        // marker-dispatch mutually exclusive.
        val cb = commandState.getAndSet(null)
        // [v2.3] Residual carry bytes (partial marker / overshoot) are NOT
        // silently dropped — they land in the stderr ring so post-mortem
        // diagnostics can see what was buffered at kill time.
        pendingCarry.getAndSet(null)?.let { carry ->
            if (carry.isNotBlank()) recordStderr("[carry-dropped] $carry")
        }
        // [D-2 companion] Queued env snapshots belong to a dead shell.
        pendingEnv.clear()
        killExecutor.execute {
            try { writer?.close() } catch (_: Exception) {}
            proc?.destroyForcibly()
            Log.i(TAG, "Persistent shell stopped (async kill)")
        }
        // Single-shot: markDone() here races a concurrent reader dispatch
        // for the same callback — only the first CAS wins; the loser's
        // onComplete never fires, so the caller's cont.resume happens once.
        if (cb != null && cb.markDone()) {
            cb.onComplete?.invoke(cb.output.toString(), -1)
        }
    }
}
