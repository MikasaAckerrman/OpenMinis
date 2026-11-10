package com.openminis.app.daemon

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [M-R1] Supervisor for the native `minisd` daemon — `libminisd.so` in
 * nativeLibraryDir, the only exec-allowed directory on API 29+ (the
 * libproot/librg precedent). Lifecycle: launch, process-level liveness,
 * graceful stop, and the crash-backoff bookkeeping
 * ([SupervisorBackoffPolicy]).
 *
 * M-R3.1 update: the class is now WIRED — TelegramVaultManager.ensureStarted
 * spawns the daemon when the `vault_impl` flag selects rust (the python
 * vault remains the default until the device-acceptance verdict; the flag
 * file is the spec's rollback contour, flip + app restart, no rebuild).
 * The restart loop consuming [nextRestartDelayMs] still waits for M-R3.3
 * (it belongs together with the session-switch workspace restart policy).
 *
 * Secret hand-off, per the reviewer's M-R1 plan: one line on the
 * child's stdin ("no secret, no service" — the daemon exits on stdin
 * EOF). The supervisor ALWAYS closes the child's stdin after the
 * optional write so a misconfigured start fails fast at the daemon
 * instead of hanging. The fd-3 design of the spec §2.1 needs the JNI
 * fork helper (ProcessBuilder closes non-standard fds) and is deferred
 * to M-R2.
 *
 * Secret hand-off, per the reviewer's M-R1 plan: one line on the
 * child's stdin ("no secret, no service" — the daemon exits on stdin
 * EOF). The supervisor ALWAYS closes the child's stdin after the
 * optional write so a misconfigured start fails fast at the daemon
 * instead of hanging. The fd-3 design of the spec §2.1 needs the JNI
 * fork helper (ProcessBuilder closes non-standard fds) and is deferred
 * to M-R2.
 */
class DaemonSupervisor(
    private val nativeLibraryDir: String,
    private val mode: String = MODE_VAULT,
    private val port: Int = 7824,
    /**
     * [M-R3.1] Optional `--storage` directory — the grammers session home
     * (`<storage>/grammers.session`). Without it the daemon defaults to
     * its CWD, which on Android is the app data dir — never what we want;
     * the vault wiring always passes filesDir/tg-vault.
     */
    private val storage: File? = null,
    /**
     * [M-R3.3] Optional `--workspace` host directory — the root the
     * workspace-rooted engine tools (multi_file_read/grep/glob) resolve
     * relative paths against. None = those tools answer the daemon's
     * honest per-call "workspace root is not configured" error. The vault
     * wiring starts WITHOUT it (the active session is unknown at boot)
     * and binds it lazily through [TelegramVaultManager.ensureWorkspaceFor],
     * which restarts the daemon when the session's workspace changes.
     */
    private val workspace: File? = null,
    private val secretProvider: () -> String? = { null },
    private val starter: (List<String>) -> Process = { command ->
        ProcessBuilder(command).start()
    },
) {

    /** The crash escalation policy the future restart loop consumes. */
    val backoff = SupervisorBackoffPolicy()

    private val processRef = AtomicReference<Process?>(null)
    private val startedAtMs = AtomicLong(0)
    private val lastRestartDelayMs = AtomicLong(0)

    /** Off the caller's thread — the 3.4s STOP-freeze lesson of
     *  PersistentShell.kt: destroy()/waitFor() must never run on main. */
    private val killExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "minisd-stop").apply { isDaemon = true }
    }

    fun binaryFile(): File = File(nativeLibraryDir, BINARY_NAME)

    /** arm64-only binary (spec §3): honest availability check — on an
     *  x86_64 (Waydroid) stand this is false and start() refuses, it
     *  does not crash. */
    fun isAvailable(): Boolean = binaryFile().isFile

    @Synchronized
    fun isAlive(): Boolean {
        val p = processRef.get() ?: return false
        if (!p.isAlive) {
            recordDeath(p)
            return false
        }
        return true
    }

    /** Idempotent: true when alive (or freshly started), false when the
     *  binary is unavailable, the spawn failed, or the daemon exited
     *  immediately (the caller can read [nextRestartDelayMs] for the
     *  backoff the future loop would apply). */
    @Synchronized
    fun start(): Boolean {
        if (isAlive()) return true
        if (!isAvailable()) return false
        val command = mutableListOf(binaryFile().absolutePath, mode, "--port", port.toString())
        storage?.let {
            command.add("--storage")
            command.add(it.absolutePath)
        }
        workspace?.let {
            command.add("--workspace")
            command.add(it.absolutePath)
        }
        val p = runCatching { starter(command) }.getOrNull() ?: return false
        // One-shot pipe write (≤64 KiB by contract — cannot block), then
        // close stdin unconditionally: the daemon reads one line, and a
        // missing secret surfaces as its stdin-EOF exit instead of a
        // silent hang.
        runCatching {
            p.outputStream.use { out ->
                secretProvider()?.let { secret ->
                    out.write((secret + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
        processRef.set(p)
        startedAtMs.set(System.currentTimeMillis())
        return true
    }

    /**
     * Graceful stop off the caller's thread: SIGTERM ([Process.destroy]),
     * 2 s backstop, SIGKILL ([Process.destroyForcibly]). An intentional
     * stop is NOT a crash — the backoff stays clean.
     */
    @Synchronized
    fun stop() {
        val p = processRef.getAndSet(null) ?: return
        startedAtMs.set(0)
        killExecutor.execute {
            runCatching {
                p.destroy() // SIGTERM
                if (!p.waitFor(STOP_BACKSTOP_MS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly() // SIGKILL
                    p.waitFor(STOP_BACKSTOP_MS, TimeUnit.MILLISECONDS)
                }
            }
            backoff.onCleanStop()
        }
    }

    /** Delay to wait before the next restart, from the last death. */
    fun nextRestartDelayMs(): Long = lastRestartDelayMs.get()

    /**
     * [M-R3.3] Bounded SYNCHRONOUS stop for the workspace restart: the
     * replacement daemon binds the SAME port, so the caller must be sure
     * the old process is gone before spawning it — the async [stop]
     * leaves a window where 7824 is still held and the new start dies
     * EADDRINUSE. Caller-thread destroy/waitFor is the PersistentShell
     * 3.4s-freeze lesson — this must stay OFF main; the only intended
     * caller is the workspace sync on Dispatchers.IO. Bound: SIGTERM,
     * 2 s backstop, SIGKILL, 500 ms reaping.
     */
    @Synchronized
    fun stopAndWait() {
        val p = processRef.getAndSet(null) ?: return
        startedAtMs.set(0)
        runCatching {
            p.destroy() // SIGTERM
            if (!p.waitFor(STOP_BACKSTOP_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly() // SIGKILL
                p.waitFor(500, TimeUnit.MILLISECONDS)
            }
        }
        backoff.onCleanStop()
    }

    private fun recordDeath(p: Process) {
        runCatching { p.exitValue() } // reap the child so it does not linger as a zombie
        val startedAt = startedAtMs.get()
        val uptime = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0
        lastRestartDelayMs.set(backoff.onDeath(uptime))
    }

    companion object {
        const val BINARY_NAME = "libminisd.so"
        const val MODE_VAULT = "vault"
        private const val STOP_BACKSTOP_MS = 2_000L

        fun from(context: Context, secretProvider: () -> String? = { null }): DaemonSupervisor =
            DaemonSupervisor(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
                secretProvider = secretProvider,
            )
    }
}
