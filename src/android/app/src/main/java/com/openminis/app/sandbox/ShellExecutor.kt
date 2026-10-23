package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Executes shell commands inside the PRoot sandbox via ProcessBuilder.
 * Corresponds to iOS ISHShellExecutor.
 */
object ShellExecutor {

    /**
     * [T-stop-button-freeze] Shared single-thread kill executor (same
     * discipline as PersistentShell): process reaping is blocking and
     * must never run on the main thread.
     */
    private val killExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "shellexec-kill").apply { isDaemon = true }
        }


    private const val TAG = "ShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 600_000L // 10 minutes

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    /** The currently running process, if any. Can be destroyed to stop execution. */
    @Volatile
    var currentProcess: Process? = null
        private set

    /**
     * [T-shellexec-concurrent-process] Every proot process this executor has
     * started and not yet reaped.
     *
     * `currentProcess` alone cannot describe reality: shell_execute runs
     * concurrently (several agent tools at once, background tasks beside the
     * chat session), and a single field loses the other runs. The set is the
     * ownership record - a process that leaves this set was reaped by its own
     * caller, so anything still in it is a process we forgot.
     */
    private val liveProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

    /**
     * [T-shellexec-concurrent-process] Reap exactly the process this call
     * started, and clear the shared slot only if it still points at it.
     */
    private fun killOwnedProcess(process: Process?) {
        if (process == null) return
        try { process.destroyForcibly() } catch (_: Throwable) {}
        liveProcesses.remove(process)
        if (currentProcess === process) currentProcess = null
    }

    /**
     * Execute a command inside the PRoot sandbox.
     *
     * @param context Android context for resolving PROOT_TMP_DIR
     * @param command Shell command string to execute
     * @param timeout Timeout in milliseconds (default 10 minutes)
     * @param environment Additional environment variables
     * @param lineCallback Called for each line of output (on IO dispatcher)
     * @return ShellResult with combined stdout+stderr, exit code, and duration
     */
    suspend fun execute(
        context: Context,
        command: String,
        timeout: Long = DEFAULT_TIMEOUT_MS,
        environment: Map<String, String> = emptyMap(),
        lineCallback: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        check(PRootKernel.isBooted) { "PRootKernel must be booted before executing commands" }

        val prootCommand = PRootKernel.buildProotCommand(command)

        Log.d(TAG, "Executing: $command")

        val startTime = System.currentTimeMillis()

        val processBuilder = ProcessBuilder(prootCommand)
        processBuilder.redirectErrorStream(true)

        // Set required environment for PRoot
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

        // Apply custom environment from PRootKernel
        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        // Apply per-call environment overrides
        for ((key, value) in environment) {
            env[key] = value
        }

        val output = StringBuilder()
        var exitCode = -1
        // [T-shellexec-concurrent-process] This invocation's own process.
        // The catch blocks below need it because `currentProcess` is a single
        // global slot shared by every concurrent run.
        var started: Process? = null

        try {
            withTimeout(timeout) {
                val process = processBuilder.start()
                started = process
                currentProcess = process
                liveProcesses.add(process)

                try {
                    // Read raw chars to preserve \r for TerminalSanitizer CR-folding.
                    // readLine() would consume \r as line terminator, losing progress overwrites.
                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
                        val buf = CharArray(4096)
                        var lastLineForCallback = StringBuilder()
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            output.append(buf, 0, n)
                            // Feed lines to callback for UI updates
                            if (lineCallback != null) {
                                for (i in 0 until n) {
                                    val c = buf[i]
                                    if (c == '\n') {
                                        lineCallback.invoke(lastLineForCallback.toString())
                                        lastLineForCallback.clear()
                                    } else if (c != '\r') {
                                        lastLineForCallback.append(c)
                                    }
                                }
                            }
                        }
                        if (lineCallback != null && lastLineForCallback.isNotEmpty()) {
                            lineCallback.invoke(lastLineForCallback.toString())
                        }
                    }

                    exitCode = process.waitFor()
                } finally {
                    // Deregister THIS process, not "whatever is in the slot".
                    //
                    // Two shell_execute calls run in parallel routinely - the
                    // agent may drive several tools at once, and background
                    // tasks run beside the chat session. With one shared field
                    // the second start overwrote the first, and the first to
                    // finish nulled out the SECOND one's reference. From that
                    // moment the second process could no longer be killed: its
                    // timeout branch called destroyForcibly() on null, so the
                    // proot process stayed alive with nobody owning it, and
                    // the STOP button killed whichever process happened to be
                    // in the slot rather than the one the user asked to stop.
                    liveProcesses.remove(process)
                    if (currentProcess === process) currentProcess = null
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Command timed out after ${timeout}ms: $command")
            // Kill this invocation's process, never `currentProcess`: with
            // concurrent runs that slot may already belong to somebody else,
            // which leaked the timed-out process AND killed an innocent one.
            killOwnedProcess(started)
            output.appendLine("\n[Command timed out after ${timeout / 1000}s]")
            exitCode = 124 // Standard timeout exit code
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            killOwnedProcess(started)
            output.appendLine("\n[Error: ${e.message}]")
            exitCode = -1
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.d(TAG, "Command completed in ${durationMs}ms with exit code $exitCode")

        ShellResult(
            output = output.toString().trimEnd(),
            exitCode = exitCode,
            durationMs = durationMs
        )
    }

    /**
     * Forcibly destroy the currently running process.
     */
    fun destroyCurrent() {
        currentProcess?.let { process ->
            currentProcess = null
            // Drop the ownership record too, otherwise the STOP button would
            // leak a Process reference per use into liveProcesses.
            liveProcesses.remove(process)
            Log.i(TAG, "Destroying current process (async kill)")
            // [T-stop-button-freeze] process reaping is blocking; the
            // STOP button path must not pay for it on the main thread.
            killExecutor.execute {
                try { process.destroyForcibly() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * [T-shellexec-concurrent-process] How many proot processes this executor
     * still owns. Non-zero after all commands have finished means one was
     * started and never reaped - the leak this class of bug produces.
     */
    internal fun liveProcessCount(): Int = liveProcesses.size
}
