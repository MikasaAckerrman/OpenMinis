package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.daemon.DaemonSupervisor
import java.io.File
import java.net.Socket
import kotlin.concurrent.thread

/**
 * [T-tg-vault] Host-side launcher for the PRIVILEGED Telegram MCP instance.
 *
 * Security model (user requirement: the agent must NEVER be able to write
 * to Telegram, mechanically, without a physical human approval):
 *
 *  1. The pyrogram session file (`tg_session.session`) — the ONLY
 *     credential that can authorize writes — is MOVED out of
 *     `minis-global/mcp-servers/srv/` (which is bind-mounted into every
 *     agent sandbox) into `filesDir/tg-vault/`, which NO agent sandbox
 *     bind-mounts and no agent process can see.
 *  2. A private proot instance is started BY THE HOST with exactly one
 *     bind (`tg-vault -> /vault`) plus the essential pseudo-filesystems.
 *     No /var/minis binds, no session workspace, no agent-visible mounts.
 *  3. That instance serves the SAME read-only tool surface over HTTP on
 *     127.0.0.1 loopback; the in-sandbox minis-mcp-cli reaches it via the
 *     stock http transport. There is no send/join code anywhere on the
 *     host side either — stage 2 (bot /start) will add an out-of-band
 *     approval dialog before any write method exists at all.
 *
 * The move is one-way and idempotent: once the session lives in the vault,
 * the agent-visible copy is gone; a stale copy left behind by a crashed
 * move is removed on the next start.
 *
 * [M-R3.1] Which implementation serves the port is the `vault_impl` flag:
 * `python` (this PRoot launcher — the default until the device-acceptance
 * verdict) or `rust` (the native minisd daemon via [DaemonSupervisor]).
 * Exactly ONE owner binds 7824 — the loser would die EADDRINUSE, so the
 * flag branch excludes the other starter, it does not layer them.
 */
object TelegramVaultManager {
    private const val TAG = "TgVault"
    private const val PORT = 7824

    /** SQLite companions of the pyrogram session — they may carry
     *  auth_key material, so they travel with the session and never
     *  stay on the agent-visible side. */
    private val COMPANION_FILES = listOf(
        "tg_session.session-journal",
        "tg_session.session-wal",
        "tg_session.session-shm"
    )

    /** Everything that must be absent from the agent sandbox after the
     *  migration (the old path must return ENOENT). */
    private val AGENT_SESSION_FILES = listOf("tg_session.session") + COMPANION_FILES

    private fun vaultDir(context: Context): File = File(context.filesDir, "tg-vault")
    private fun srcSrvDir(context: Context): File =
        File(context.filesDir, "minis-global/mcp-servers/srv")

    // ── [M-R3.1] vault_impl: rust | python ─────────────────────────────

    /**
     * The flag file name inside the vault dir. Flip protocol: the clone
     * build is debuggable, so
     *   adb shell run-as com.openminis.app.clone sh -c \
     *     'echo -n rust > files/tg-vault/vault_impl'
     * + an app restart switches the 7824 server WITHOUT a rebuild. This is
     * the migration spec's rollback contour: python stays until the
     * device-acceptance verdict, then the flag, the python launcher and
     * its rootfs dependencies are deleted together (no eternal fallbacks).
     */
    private const val FLAG_FILE = "vault_impl"
    internal const val IMPL_PYTHON = "python"
    internal const val IMPL_RUST = "rust"

    /**
     * Deny-by-default: anything but exactly "rust" (whitespace/case
     * tolerant) keeps the legacy python path. A HALF-wired daemon must
     * not become the default just because a file holds garbage.
     */
    internal fun parseVaultImpl(raw: String?): String =
        if (raw?.trim()?.lowercase() == IMPL_RUST) IMPL_RUST else IMPL_PYTHON

    /** The active impl for this app run: read the flag file once per
     *  start. An unreadable file degrades to python (the safe default). */
    internal fun activeVaultImpl(vaultDir: File): String = try {
        parseVaultImpl(File(vaultDir, FLAG_FILE).takeIf { it.exists() }?.readText())
    } catch (e: Exception) {
        Log.w(TAG, "vault_impl flag unreadable: ${e.message}")
        IMPL_PYTHON
    }

    /**
     * [T-vault-auth] The bearer secret of the vault HTTP gate: generated once
     * into filesDir/tg-vault/auth.key (app-private storage, never inside any
     * agent-visible bind). Empty string = gate disabled (file unavailable) —
     * the vault degrades to today's unauthenticated loopback instead of
     * breaking the whole Telegram surface. Rationale: loopback is NOT a
     * trust boundary on Android — any app holding INTERNET can reach another
     * app's 127.0.0.1 port, so the privileged Telegram reader must
     * authenticate its callers.
     */
    /**
     * [B42-task4] internal: the profile sync rides the SAME gate — one
     * secret, one auth model, no second copy of the key material.
     */
    internal fun authSecret(context: Context): String = try {
        val f = File(vaultDir(context), "auth.key")
        val existing = f.takeIf { it.exists() }?.readText()?.trim()
        if (!existing.isNullOrEmpty()) existing else {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            f.parentFile?.mkdirs()
            f.writeText(hex)
            hex
        }
    } catch (e: Exception) {
        Log.w(TAG, "vault auth secret unavailable: ${e.message}")
        ""
    }

    /**
     * Liveness probe that verifies the responder IS our telegram MCP
     * (a bare port probe would false-positive on any process bound to
     * 7824 and silently leave the agent without a server).
     */
    fun isAlive(context: Context): Boolean = try {
        Socket("127.0.0.1", PORT).use { s ->
            s.soTimeout = 4000
            // [T-vault-auth] The gate (when armed) needs the bearer secret in
            // the probe too — an unauthenticated initialize would now read
            // 401 and read as "dead".
            val authHeader = authSecret(context)
                .takeIf { it.isNotEmpty() }?.let { "Authorization: Bearer $it\r\n" } ?: ""
            val probe = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                authHeader +
                "Connection: close\r\n" +
                "Content-Length: 46\r\n\r\n" +
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\"}"
            s.getOutputStream().apply {
                write(probe.toByteArray(Charsets.UTF_8)); flush()
            }
            val reply = s.getInputStream().bufferedReader().use { it.readText() }
            reply.contains("\"telegram\"")
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Idempotent, crash-safe startup. Call from MinisApp after PRootKernel
     * boot (rootfs present). Never throws — vault failures degrade to the
     * agent-side STDIO server NOT having a session (which the user sees as
     * "Not logged in"), never to an app crash.
     */
    fun ensureStarted(context: Context) {
        // [T-tg-accounts] Stage A: register the single-account-era session in
        // the accounts registry (idempotent; the vault server still reads the
        // classic path until stage B moves it to per-account dirs).
        runCatching {
            TelegramAccountStore(vaultDir(context))
                .migrateLegacyIfNeeded(File(vaultDir(context), "tg_session.session"))
        }.onFailure { Log.w(TAG, "accounts migration failed: ${it.message}") }
        // [M-R3.1] The flag selects exactly ONE owner of 7824 — the loser
        // would die EADDRINUSE, so the branch excludes the other starter.
        if (activeVaultImpl(vaultDir(context)) == IMPL_RUST) {
            ensureRustStarted(context)
            return
        }
        if (isAlive(context)) return
        thread(name = "tg-vault-start") {
            try {
                startLocked(context)
            } catch (e: Exception) {
                Log.e(TAG, "vault start failed: ${e.message}")
            }
        }
    }

    /**
     * [M-R3.1] The native daemon path: no PRoot, no python — `libminisd.so`
     * exec'd straight from nativeLibraryDir (the only exec-allowed
     * directory on API 29+, the libproot/librg precedent). Same port, same
     * bearer secret, same probe contract: rpc.rs answers `initialize` with
     * serverInfo.name="telegram", so [isAlive] and every existing caller
     * (login screen, profile sync, agent CLI) work against BOTH impls
     * unchanged. `--workspace` is deliberately NOT passed yet: it must
     * match the ACTIVE session's workspace, which changes across sessions
     * — the restart-on-session-switch policy lands with M-R3.3 (SearchTools
     * routing); until then the workspace-rooted tools answer the daemon's
     * honest per-call "workspace root is not configured" error.
     */
    @Volatile private var rustSupervisor: DaemonSupervisor? = null

    private fun ensureRustStarted(context: Context) {
        val vault = vaultDir(context)
        val supervisor = synchronized(this) {
            rustSupervisor ?: DaemonSupervisor(
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
                secretProvider = { authSecret(context).ifEmpty { null } },
                storage = vault,
                starter = { command ->
                    val pb = ProcessBuilder(command).apply {
                        // [M-R3] Observability per the spec: the daemon log
                        // next to the python vault's server.log. The error
                        // stream is merged, so EADDRINUSE and the stdin
                        // secret-refusal land in the file, not in limbo.
                        redirectErrorStream(true)
                        redirectOutput(File(vault, "minisd.log"))
                    }
                    try {
                        val repo = com.openminis.app.data.repository.EnvVarRepository(context)
                        repo.getValue("TG_API_ID")?.let { pb.environment()["TG_API_ID"] = it }
                        repo.getValue("TG_API_HASH")?.let { pb.environment()["TG_API_HASH"] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "env-var read failed (daemon serves the session-error surface): ${e.message}")
                    }
                    pb.start()
                },
            ).also { rustSupervisor = it }
        }
        if (supervisor.isAlive()) return
        if (!supervisor.isAvailable()) {
            Log.w(TAG, "libminisd.so not in nativeLibraryDir — daemon start skipped")
            return
        }
        thread(name = "minisd-start") {
            try {
                // Impl-agnostic self-heal: both impls serve the same port
                // and the same bearer contract, so the servers.json header
                // publish is not a python-only concern.
                publishAuthHeader(context)
                val ok = supervisor.start()
                if (!ok) {
                    Log.e(TAG, "minisd spawn failed (see minisd.log)")
                    return@thread
                }
                // Give a fast-failing bind (EADDRINUSE against a foreign
                // server left on 7824) a beat to surface, then report the
                // port truthfully instead of guessing.
                Thread.sleep(600)
                if (supervisor.isAlive()) {
                    Log.i(TAG, "minisd daemon started on 127.0.0.1:$PORT (log: minisd.log)")
                } else {
                    Log.e(TAG, "minisd exited immediately; 7824 held by: " +
                        (if (isAlive(context)) "another server (foreign/other impl)" else "nothing"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "minisd start failed: ${e.message}")
            }
        }
    }

    /**
     * [M-R3.2] A login just completed. The Rust daemon fixes its session
     * state at boot (VaultState.live/dead), so a freshly written
     * grammers.session is invisible to the RUNNING process — the daemon
     * must restart to pick it up. No-op for the python impl: its server
     * gets the swept pyrogram session the way it always did.
     */
    fun onLoginCompleted(context: Context) {
        if (activeVaultImpl(vaultDir(context)) != IMPL_RUST) return
        val supervisor = synchronized(this) { rustSupervisor } ?: return
        thread(name = "minisd-restart") {
            try {
                supervisor.stop()
                // SIGTERM + the 2s stop-backstop must land before the
                // replacement process can re-bind 7824.
                Thread.sleep(2500)
                if (supervisor.start()) {
                    Log.i(TAG, "minisd restarted after login (log: minisd.log)")
                } else {
                    Log.e(TAG, "minisd restart after login failed (see minisd.log)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "minisd restart failed: ${e.message}")
            }
        }
    }

    /**
     * [T-vault-auth] Publish the gate secret to the only configured HTTP
     * callers: every MCP entry pointed at the vault port receives an
     * Authorization header in servers.json (the CLI's http transport reads
     * server.headers verbatim). The agent CAN read servers.json —
     * acceptable by design: the vault surface is read-only, and the threat
     * model here is OTHER PHONE APPS, which never see filesDir. Runs for
     * BOTH impls (same port, same secret, same 401 semantics).
     */
    private fun publishAuthHeader(context: Context) {
        val auth = authSecret(context)
        if (auth.isEmpty()) return
        runCatching {
            val repo = com.openminis.app.data.repository.MCPRepository(context)
            repo.servers.value
                .filter { it.url?.contains(":$PORT") == true }
                .filter { it.headers["Authorization"] != "Bearer $auth" }
                .forEach { stale ->
                    repo.update(stale.copy(headers = stale.headers + ("Authorization" to "Bearer $auth")))
                }
        }.onFailure { Log.w(TAG, "vault auth header publish failed: ${it.message}") }
    }

    private fun startLocked(context: Context) {
        val rm = RootfsManager.getInstance(context)
        if (!rm.rootfsDir.exists() || !rm.prootBinary.exists()) {
            Log.w(TAG, "rootfs/proot not ready — vault start skipped")
            return
        }
        val vault = vaultDir(context)
        val src = srcSrvDir(context)
        val serverSrc = File(src, "telegram.py")
        if (!serverSrc.exists()) {
            Log.w(TAG, "telegram.py not found in mcp-servers/srv — nothing to vault")
            return
        }
        vault.mkdirs()
        publishAuthHeader(context)

        // 1. Keep the vault server code in sync with the maintained copy
        //    (the agent-side /var/minis/mcp-servers/srv/telegram.py stays
        //    the single source of truth the user maintains).
        try {
            serverSrc.copyTo(File(vault, "telegram.py"), overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "server code sync failed: ${e.message}")
            return
        }

        // 2. Move the session INTO the vault (one-way). If a live agent-side
        //    server holds the SQLite session, rename fails — retry next start.
        if (!migrateSession(src, vault)) return
        // Even with NO session the server must run: read-only tools work,
        // the CLI reaches it (instead of ECONNREFUSED), and a faulted/
        // fresh login has somewhere to happen. Skipping the launch here
        // would deadlock re-login forever.

        // 3. Launch the private proot instance (host-owned, vault-only binds).
        val cmd = mutableListOf<String>()
        cmd.add(rm.prootBinary.absolutePath)
        cmd.add("-0")
        cmd.add("--link2symlink")
        cmd.add("-r"); cmd.add(rm.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-b"); cmd.add("${vault.absolutePath}:/vault")
        cmd.add("-w"); cmd.add("/vault")
        cmd.add("/usr/bin/python3")
        cmd.add("/vault/telegram.py")
        cmd.add("--serve-http")
        cmd.add("127.0.0.1:$PORT")

        val logFile = File(vault, "server.log")
        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            redirectOutput(logFile)
        }
        val env = pb.environment()
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
        // [T-vault-auth] Arm the python-side gate; empty secret = no gate
        // (see authSecret) — the server keeps today's behaviour instead of
        // 401-ing every caller.
        val auth = authSecret(context)
        if (auth.isNotEmpty()) env["TG_VAULT_AUTH"] = auth
        env["TG_SESSION_DIR"] = "/vault"
        // API credentials come from the encrypted env-var store via the
        // host — they never transit the agent sandbox environment.
        try {
            val repo = com.openminis.app.data.repository.EnvVarRepository(context)
            repo.getValue("TG_API_ID")?.let { env["TG_API_ID"] = it }
            repo.getValue("TG_API_HASH")?.let { env["TG_API_HASH"] = it }
        } catch (e: Exception) {
            Log.w(TAG, "env-var read failed (vault server may lack credentials): ${e.message}")
        }
        pb.start()
        Log.i(TAG, "vault MCP launched on 127.0.0.1:$PORT (pid logged in ${logFile.name})")
    }

    /**
     * One-way session migration into the vault.
     *
     * Failure modes are covered explicitly:
     *  - COPY FIRST: the vault copy is written and size-verified before a
     *    single byte is removed from the agent-visible side. A crash
     *    mid-copy leaves the original intact (retried on the next start).
     *  - THE WHOLE SQLITE SET MOVES: .session plus its -journal/-wal/-shm
     *    companions — a leftover WAL can carry auth_key material, so it
     *    must not stay on the agent side.
     *  - UNLINK IS ATOMIC per file → the agent-visible path returns
     *    ENOENT right away; no second working copy is left behind.
     *  - if the vault already holds a session, any agent-side copy is a
     *    stale duplicate and gets deleted (vault stays authoritative).
     */
    fun migrateSession(srcDir: File, vaultDir: File): Boolean {
        if (!vaultDir.exists() && !vaultDir.mkdirs()) {
            Log.e(TAG, "vault dir cannot be created")
            return false
        }
        val dstSession = File(vaultDir, "tg_session.session")
        if (dstSession.exists()) {
            purgeAgentCopies(srcDir)
            return true // vault copy is authoritative
        }
        val srcSession = File(srcDir, "tg_session.session")
        if (!srcSession.exists()) return false

        val set = ArrayList<File>()
        set.add(srcSession)
        for (n in COMPANION_FILES) {
            val f = File(srcDir, n)
            if (f.exists()) set.add(f)
        }
        // 1) copy the whole set into the vault
        for (f in set) {
            val dst = File(vaultDir, f.name)
            try {
                f.copyTo(dst, overwrite = true)
            } catch (e: Exception) {
                Log.e(TAG, "copy of ${f.name} failed: ${e.message}")
                return false // original still intact
            }
        }
        // 2) verify byte counts before touching the original
        for (f in set) {
            val dst = File(vaultDir, f.name)
            if (!dst.exists() || dst.length() != f.length()) {
                Log.e(TAG, "vault copy of ${f.name} failed verification")
                return false // original still intact
            }
        }
        // 3) remove the agent-visible originals (atomic unlink each)
        purgeAgentCopies(srcDir)
        // 4) the whole point: the old path must be gone
        if (File(srcDir, "tg_session.session").exists()) {
            Log.e(TAG, "session STILL visible to the agent after migration")
            return false
        }
        Log.i(TAG, "tg_session.session migrated to vault — agent no longer has it")
        return true
    }

    /** Deletes the session and every SQLite companion from the agent side. */
    private fun purgeAgentCopies(srcDir: File) {
        for (n in AGENT_SESSION_FILES) {
            val f = File(srcDir, n)
            if (f.exists()) {
                if (f.delete()) {
                    Log.i(TAG, "agent-visible $n removed")
                } else {
                    Log.e(TAG, "FAILED to remove agent-visible $n")
                }
            }
        }
    }

    /** Test seam alias so the unit test reads naturally. */
    fun migrateSessionForTest(srcDir: File, filesRoot: File): Boolean =
        migrateSession(srcDir, File(filesRoot, "tg-vault"))
}
