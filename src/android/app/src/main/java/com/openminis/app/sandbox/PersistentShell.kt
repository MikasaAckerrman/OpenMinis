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
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * @Volatile-imports APPLIED (D-1..D-10 protocol patch, v2.3 design):
 * PersistentShellProtocolDriver (protocol owner: ShellProtocolEngine, resume
 * registry with atomic per-marker continuation claims, reader loop, EOF/stop
 * teardown, process fields with the W-3 restart-race guard) and the write Mutex
 * serializing executeCommand + applyEnvironment (write-side only). Extracted
 * from the previous all-in-one class so integration acceptance tests can
 * drive the wiring on the JVM without Android Context (docs 214-232).
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

    // [WIRING extraction] Protocol-driving half split into
    // PersistentShellProtocolDriver: the engine, the atomic resume registry
    // (single-resumer claims), the reader loop, EOF/stop teardown and the
    // process fields (incl. the W-3 restart-race guard) live there. This
    // class keeps shell process SPAWNING (Context/RootfsManager/proot), the
    // write mutex + suspension frame, env queue, stderr ring, kill executor.
    private val driver = PersistentShellProtocolDriver(diagnostics = ::recordStderr)
    private var process: Process?
        get() = driver.process
        set(value) { driver.process = value }
    // [T-persistent-shell-threads] Reader threads, kept on purpose.
    //
    // They used to be created, started and dropped on the floor:
    // Thread({...}).apply { start() } keeps no reference anywhere, so stop()
    // could neither interrupt them nor prove they had ended. They normally die
    // because the pipe closes when proot is destroyed, but that leaves a
    // failure invisible: if the kill does not land, the reader stays blocked
    // in readLine forever holding this shell.
    //
    // In debug builds a shell runs TWO of them (stdout + stderr), which is the
    // per-shell thread cost that adds up when shells are not reaped. Holding
    // the references makes it measurable and lets stop() interrupt as a
    // backstop when the pipe does not close.
    @Volatile private var readerThread: Thread? = null
    @Volatile private var stderrThread: Thread? = null

    private var stdinWriter: BufferedWriter?
        get() = driver.stdinWriter
        set(value) { driver.stdinWriter = value }

    private val isStarting = AtomicBoolean(false)

    /**
     * [WIRING, D-1..D-3/D-10] THE single protocol state owner. Marker parsing,
     * carry folding, drain of cancelled commands, exit-zone parking, dispatch
     * CAS — all live in [ShellProtocolEngine] (its own JUnit coverage:
     * ShellProtocolEngineTest s1..s9, 10/10 green at e66ec5f). PersistentShell
     * keeps ONLY shell process management (start/stop/streams/steer) and
     * caller-side serialization ([writeMutex]) — zero duplicate protocol
     * logic remains in this class (acceptance 01).
     */
    private val protocol: ShellProtocolEngine get() = driver.protocol

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

    // [WIRING extraction] Activation records (marker -> suspended caller's
    // callback) moved into the driver's atomic resume registry; the shell
    // reaches them only through the driver claim API (single-resumer model).



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

    // [WIRING extraction] CommandCallback now lives in the driver
    // (internal class, same CAS single-shot contract) — see
    // PersistentShellProtocolDriver.CommandCallback.

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
        readerThread = Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat AND into a bounded
        // ring buffer so a start failure can be explained in the chat itself.
        if (debugOffload) {
            stderrThread = Thread({
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

    // [WIRING extraction] The reader loop and the EOF teardown live in the
    // driver now (PersistentShellProtocolDriver.readLoop) — verbatim move,
    // including the W-3 restart-race guard and the single-resumer claims.
    private fun readLoop(p: Process) = driver.readLoop(p)

    /**
     * [WIRING, acceptance 01] The ONLY place where a Dispatch's marker is
     * matched back to the suspended caller's callback. The engine returned
     * the dispatch; the shell matches it against the callback it armed for
     * that same marker, keeping the activation record (resume continuation)
     * on the shell side while the protocol state stays engine-side.
     */
    private fun claimForResume(marker: String): PersistentShellProtocolDriver.CommandCallback? =
        driver.claimForResume(marker)

    private fun armForResume(marker: String, cb: PersistentShellProtocolDriver.CommandCallback) =
        driver.armForResume(marker, cb)

    private fun dropResume(marker: String) = driver.dropResume(marker)

    /** [stop-path] Atomically harvest every still-armed activation record.
     *  Each removed entry is ours to resume: a concurrent reader Dispatch
     *  finds its entry already gone (claimForResume -> null) and skips —
     *  exactly one side of the race resumes. */
    private fun claimAllResumes(): List<PersistentShellProtocolDriver.CommandCallback> =
        driver.claimAllResumes()

    /** [stop-path] The in-flight callback harvested from the protocol may or
     *  may not still be armed in the registry (reader may have claimed it a
     *  nanosecond earlier with a real Dispatch). Claim it back; if we won,
     *  resume with death — if the reader won, it resumed with real output. */
    private fun dropResumeIfArmed(cb: ShellProtocolEngine.CommandCallbackContract?) =
        driver.dropResumeIfArmed(cb)

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

            val outcome: Pair<String, Int>? = withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeout) {
                    suspendCancellableCoroutine { cont ->
                        val cb = driver.createCallback(
                            marker = marker,
                            lineCallback = lineCallback,
                        )
                        // [wiring, D-2/D-10] Single completion ownership:
                        // reader dispatch = claimForResume + CAS markDone;
                        // EOF/stop = detachCurrent + markDone; timeout/cancel
                        // = null outcome branch. Exactly one path resumes.
                        cb.resume = { output, exitCode ->
                            if (cont.isActive) {
                                cont.resume(Pair(output, exitCode))
                            }
                        }
                        armForResume(marker, cb)

                        // [F2 writer-gate] start() throws ISE ("draining")
                        // when the previous command's drain is still open —
                        // mapped here to the shell-unusable result the
                        // acceptance scenarios require (explicit failure,
                        // never silent contamination).
                        try {
                            protocol.start(cb)
                        } catch (ise: IllegalStateException) {
                            dropResume(marker)
                            if (cont.isActive) {
                                cont.resume(Pair("[Shell unusable: pending drain]", 124))
                            }
                            return@suspendCancellableCoroutine
                        }

                        cont.invokeOnCancellation {
                            // [v2.3] Detach (mark drain) only if we still own
                            // the slot; engine closes the drain via feed.
                            protocol.detachCurrent()
                            dropResume(marker)
                        }

                        try {
                            writer.write(wrappedCommand)
                            writer.flush()
                        } catch (e: Exception) {
                            protocol.detachCurrent()
                            dropResume(marker)
                            if (cont.isActive) {
                                cont.resume(Pair("[Write error: ${e.message}]", -1))
                            }
                        }
                    }
                }
            }

            if (outcome == null) {
                // [D-1 timeout] The command keeps running shell-side; the
                // cancellation handler (invokeOnCancellation) already ran
                // detachCurrent+dropResume when the continuation died, and
                // the null-outcome branch is the ONLY resumer here (returns
                // 124 to the caller). No continuation resume races: the
                // continuation already returned at this point.
                protocol.detachCurrent()
                dropResume(marker)
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
            if (protocol.marker != null) {
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
        // [v2.3, WIRING extraction] Protocol teardown (parked fragments to
        // diagnostics, in-flight claim, registry harvest, single-resumer
        // death resumes) lives in the driver now — verbatim move.
        driver.teardownForStop()
        // [D-2 companion] Queued env snapshots belong to a dead shell.
        pendingEnv.clear()
        killExecutor.execute {
            try { writer?.close() } catch (_: Exception) {}
            proc?.destroyForcibly()
            // [T-persistent-shell-threads] Backstop only. The threads end on
            // their own once destroyForcibly closes the pipes, which is the
            // normal path; interrupting covers the case where the kill did not
            // land, where the reader would otherwise sit in readLine forever
            // holding this shell. InterruptedException is handled inside the
            // loops, so this cannot turn into a crash.
            interruptReaderThreads()
            Log.i(TAG, "Persistent shell stopped (async kill)")
        }
    }

    /**
     * [T-persistent-shell-threads] Interrupt both reader threads and forget
     * them, so a shell that is stopped stops being a thread owner.
     */
    private fun interruptReaderThreads() {
        readerThread?.let { if (it !== Thread.currentThread()) { try { it.interrupt() } catch (_: Throwable) {} } }
        stderrThread?.let { if (it !== Thread.currentThread()) { try { it.interrupt() } catch (_: Throwable) {} } }
        readerThread = null
        stderrThread = null
    }

    /**
     * [T-persistent-shell-threads] Reader threads still alive.
     *
     * Zero after stop() has run and the process died. A non-zero count after
     * every shell of a session has been terminated is the thread-leak signal
     * that used to have no way to be observed at all.
     */
    fun liveReaderThreadCount(): Int {
        var n = 0
        readerThread?.let { if (it.isAlive) n++ }
        stderrThread?.let { if (it.isAlive) n++ }
        return n
    }
}
