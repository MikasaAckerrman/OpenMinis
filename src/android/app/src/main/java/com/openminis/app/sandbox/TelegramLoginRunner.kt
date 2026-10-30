package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [T-tg-login-host] Host-side runner for the Telegram login driver
 * (`mcp-servers/srv/tg-login.py` — status / send-code / verify / password).
 *
 * WHY THIS EXISTS. The login screen used to start the driver with a bare
 * `ProcessBuilder("python3", "/var/minis/…")`. That could never work from
 * the app process: `/var/minis` only exists inside the agent PRoot sandbox
 * and the app has no `python3` on its PATH. The IOException was swallowed
 * by the screen's `runCatching { }.getOrDefault("")`, every call returned
 * an empty JSON, and the Send code button did nothing — the silent
 * dead-button bug.
 *
 * The fix mirrors [TelegramVaultManager.startLocked]: the HOST launches a
 * private PRoot instance over the same rootfs. The sandbox's srv dir is
 * bind-mounted at its canonical in-guest path so the driver keeps its
 * original path contract, and the driver runs with the app's own
 * environment. After every run, [sweepSessionIntoVault] moves any session
 * Pyrogram wrote under srv into `filesDir/tg-vault`, so the live
 * credential never stays agent-visible (the boot-time migration in
 * [TelegramVaultManager] remains the backstop).
 *
 * API credentials: values passed by the caller (typed on the screen) win;
 * otherwise they are pulled from the encrypted env-var store — the same
 * source the vault server uses. They transit the app process only, never
 * an agent sandbox.
 */
object TelegramLoginRunner {
    private const val TAG = "TgLogin"

    /** Outcome of one driver run: merged stdout+stderr and the exit code. */
    data class Result(val exitCode: Int, val output: String)

    /**
     * Run `tg-login.py <args…>` inside a host-owned PRoot instance.
     *
     * Never throws: on any setup or start failure the result carries a
     * negative exit code and a JSON error line the caller can surface.
     * Negative exit codes: -1 setup missing, -2 timeout, -3 start failed.
     */
    fun run(
        context: Context,
        args: List<String>,
        apiId: String? = null,
        apiHash: String? = null,
        timeoutSec: Long = 90,
    ): Result {
        val rm = RootfsManager.getInstance(context)
        if (!rm.rootfsDir.exists() || !rm.prootBinary.exists()) {
            return Result(-1, "{\"error\":\"rootfs/proot not ready — driver cannot start\"}")
        }
        val srv = File(context.filesDir, "minis-global/mcp-servers/srv")
        if (!File(srv, "tg-login.py").exists()) {
            return Result(-1, "{\"error\":\"tg-login.py not found in mcp-servers/srv\"}")
        }

        val cmd = mutableListOf<String>().apply {
            add(rm.prootBinary.absolutePath)
            add("-0")
            add("--link2symlink")
            add("-r"); add(rm.rootfsDir.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            // Canonical in-guest path: the driver keeps its original
            // path contract regardless of where it resolves itself.
            add("-b"); add("${srv.absolutePath}:/var/minis/mcp-servers/srv")
            add("-w"); add("/var/minis/mcp-servers/srv")
            add("/usr/bin/python3")
            add("/var/minis/mcp-servers/srv/tg-login.py")
            addAll(args)
        }
        val pb = ProcessBuilder(cmd).apply { redirectErrorStream(true) }
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
        // Screen-typed credentials win; otherwise the encrypted env-var
        // store — the same source the vault server uses.
        apiId?.takeIf { it.isNotBlank() }?.let { env["TG_API_ID"] = it }
            ?: envValue(context, "TG_API_ID")?.let { env["TG_API_ID"] = it }
        apiHash?.takeIf { it.isNotBlank() }?.let { env["TG_API_HASH"] = it }
            ?: envValue(context, "TG_API_HASH")?.let { env["TG_API_HASH"] = it }

        return try {
            val p = pb.start()
            // Drain on a side thread: readText blocks until EOF, so a
            // hung driver must not outrun the waitFor timeout below.
            var out = ""
            val reader = thread(name = "tg-login-out") {
                try {
                    out = p.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    // Stream torn down by the timeout path — nothing to do.
                }
            }
            val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!done) {
                p.destroy()
                reader.join(2000)
                if (p.isAlive) p.destroyForcibly()
                Result(-2, out + "\n{\"error\":\"driver timed out after ${timeoutSec}s\"}")
            } else {
                reader.join(5000)
                Result(p.exitValue(), out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "driver run failed: ${e.message}")
            Result(-3, "{\"error\":\"driver start failed: ${e.message}\"}")
        }
    }

    /**
     * Move any session Pyrogram left under mcp-servers/srv into the vault.
     * Cheap no-op when nothing appeared; closes the window where the live
     * credential sits agent-visible after a fresh login.
     */
    fun sweepSessionIntoVault(context: Context) {
        try {
            val srv = File(context.filesDir, "minis-global/mcp-servers/srv")
            TelegramVaultManager.migrateSession(srv, File(context.filesDir, "tg-vault"))
        } catch (e: Exception) {
            Log.w(TAG, "session sweep failed: ${e.message}")
        }
    }

    private fun envValue(context: Context, key: String): String? = try {
        EnvVarRepository(context).getValue(key)
    } catch (e: Exception) {
        Log.w(TAG, "env-var read failed for $key: ${e.message}")
        null
    }
}
