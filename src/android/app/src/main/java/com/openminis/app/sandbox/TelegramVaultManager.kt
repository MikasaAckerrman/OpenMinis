package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
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

    /**
     * Liveness probe that verifies the responder IS our telegram MCP
     * (a bare port probe would false-positive on any process bound to
     * 7824 and silently leave the agent without a server).
     */
    fun isAlive(): Boolean = try {
        Socket("127.0.0.1", PORT).use { s ->
            s.soTimeout = 4000
            val probe = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\nConnection: close\r\n" +
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
        if (isAlive()) return
        thread(name = "tg-vault-start") {
            try {
                startLocked(context)
            } catch (e: Exception) {
                Log.e(TAG, "vault start failed: ${e.message}")
            }
        }
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
        if (!File(vault, "tg_session.session").exists()) return // nothing to serve yet

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
