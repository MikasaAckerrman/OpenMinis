package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.daemon.DaemonSupervisor
import com.openminis.app.data.repository.EnvVarRepository
import java.io.File
import org.json.JSONObject

/**
 * The runner speaks the python driver's EXACT result type: the login
 * screen's runLogin branches between the two transports, and one type
 * keeps the joined result's `.output` / `.exitCode` resolved (two
 * same-named data classes would erase to Any at the if/else join).
 */
internal typealias MinisdLoginResult = TelegramLoginRunner.Result

/**
 * [M-R3.2] The Kotlin half of the grammers login bridge — reviewer verdict:
 * variant (a). The interactive `minisd login` process (one stdin/stdout
 * session: phone → code → optional 2FA password, client.rs run_login)
 * stays ALIVE between the login screen's steps, so the grammers
 * phone-code token never has to survive a process restart and the frozen
 * RPC contract v1 stays untouched.
 *
 * The runner speaks the SAME step contract as the python driver
 * ([TelegramLoginRunner]): one call per screen step, a `RESULT {json}`
 * line on the output, the keys the screen already parses (`code_sent`,
 * `needs_password`, `logged_in`, `error`). The screen branches on the
 * `vault_impl` flag and nothing else changes.
 *
 * Prompt protocol (client.rs, everything on stderr, merged by
 * redirectErrorStream):
 *   "minisd login: enter phone (international format):"
 *   "minisd login: enter login code:"
 *   "minisd: 2FA password required (hint: …)" → "minisd login: enter 2FA password:"
 *   "minisd: session saved to <path>" + exit 0 on success
 *   "minisd: session is already authorized, nothing to do" + exit 0
 *   "minisd: <error>" + non-zero exit on failure.
 *
 * The phone is written into stdin IMMEDIATELY after spawn (the pipe
 * buffers it until the daemon's first read — no prompt race), so one
 * send-code call = spawn + write + await the code prompt. The code is
 * consumed by the FIRST verify call; the follow-up verify-with-password
 * writes ONLY the password (the daemon sits at the password prompt by
 * then — writing the code again would feed it as the password).
 */
object MinisdLoginRunner {
    private const val TAG = "MinisdLogin"

    // Exact prompt/save markers of client.rs (prompt_stdin + run_login).
    internal const val M_PHONE = "minisd login: enter phone (international format):"
    internal const val M_CODE = "minisd login: enter login code:"
    internal const val M_PASSWORD = "minisd login: enter 2FA password:"
    internal const val M_SAVED = "minisd: session saved to "
    internal const val M_ALREADY = "session is already authorized"

    // Session phases: where the interactive process currently sits.
    internal const val PH_AWAIT_CODE = "await_code"
    internal const val PH_AWAIT_PASSWORD = "await_password"
    internal const val PH_DONE = "done"

    /** What the awaited daemon turn produced. */
    internal sealed interface Await {
        data class Marker(val text: String) : Await
        data class Exit(val code: Int) : Await
        object Timeout : Await
    }

    /** The step's verdict: the phase the session sits in afterwards and
     *  the driver-compatible JSON for the screen. */
    internal data class StepOutcome(val phase: String, val json: JSONObject)

    // ── Pure step translation (unit-tested) ─────────────────────────────

    /**
     * (step, phase before, what the daemon did, output tail) → verdict.
     * Pure: no process, no Android types.
     */
    internal fun verdictFor(
        step: String,
        phaseBefore: String,
        await: Await,
        tail: String,
    ): StepOutcome {
        val failed = { msg: String ->
            StepOutcome(PH_DONE, JSONObject().put("error", msg))
        }
        return when {
            // The phone is written at spawn; the daemon answering with the
            // code prompt means the request went through. An immediate
            // "already authorized" is honest dead-end for a code request.
            step == "send-code" -> when (await) {
                is Await.Marker -> if (await.text == M_CODE) {
                    StepOutcome(PH_AWAIT_CODE, JSONObject().put("code_sent", true))
                } else {
                    failed("Session already authorized — no code needed; reload the screen")
                }
                is Await.Exit -> failed(errorTail(tail))
                Await.Timeout -> failed("daemon did not answer within the timeout (MTProto connect?)")
            }

            // First verify: the code goes in; the daemon either accepts it
            // and asks for the 2FA password, or completes the sign-in.
            step == "verify" && phaseBefore != PH_AWAIT_PASSWORD -> when (await) {
                is Await.Marker -> if (await.text == M_PASSWORD) {
                    StepOutcome(PH_AWAIT_PASSWORD, JSONObject().put("needs_password", true))
                } else {
                    StepOutcome(PH_DONE, JSONObject().put("logged_in", true))
                }
                is Await.Exit ->
                    if (await.code == 0 && tail.contains(M_ALREADY)) {
                        StepOutcome(PH_DONE, JSONObject().put("logged_in", true))
                    } else {
                        failed(errorTail(tail))
                    }
                Await.Timeout -> failed("daemon did not answer within the timeout (wrong code?)")
            }

            // verify with the password: the daemon sits at the password
            // prompt; only the sign-in result can follow.
            else -> when (await) {
                is Await.Marker -> StepOutcome(PH_DONE, JSONObject().put("logged_in", true))
                is Await.Exit -> failed(errorTail(tail))
                Await.Timeout -> failed("daemon did not answer within the timeout (wrong password?)")
            }
        }
    }

    /** The error text the screen shows: the last output lines — the
     *  daemon prints the reason just before dying. */
    internal fun errorTail(full: String): String =
        full.trim().lines().filter { it.isNotBlank() }.takeLast(3)
            .joinToString(" ") { it.trim() }
            .take(200)
            .ifBlank { "login failed without output" }

    /** Screen-step argv → (step, phone, code, password). The python
     *  argparse flags the screen prepends are ignored: the daemon takes
     *  its credentials from the environment. */
    internal fun parseArgs(args: List<String>): StepArgs {
        var step = args.firstOrNull()?.takeIf { it != "--api-id" } ?: ""
        var phone: String? = null
        var code: String? = null
        var password: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--phone" -> { phone = args.getOrNull(i + 1); i += 2 }
                "--code" -> { code = args.getOrNull(i + 1); i += 2 }
                "--password" -> { password = args.getOrNull(i + 1); i += 2 }
                // --api-id/--api-hash (and their values) are python-only.
                "--api-id", "--api-hash" -> i += 2
                else -> i += 1
            }
        }
        return StepArgs(step, phone, code, password)
    }

    internal data class StepArgs(
        val step: String,
        val phone: String?,
        val code: String?,
        val password: String?,
    )

    /** The `status` verdict, composed Kotlin-side (the daemon has no
     *  status subcommand and spawning an MTProto connect for a UI probe
     *  would be the slow path). `has_saved_creds` stays false: the Rust
     *  flow keeps no driver-side credentials store — the screen's own
     *  encrypted state pre-fills the fields regardless. */
    internal fun statusJson(hasGrammers: Boolean, hasAccounts: Boolean, envReady: Boolean): String {
        val json = JSONObject()
            .put("logged_in", hasGrammers || hasAccounts)
            .put("env_ready", envReady)
            .put("has_saved_creds", false)
        return "RESULT $json"
    }

    // ── The interactive session ─────────────────────────────────────────

    /**
     * One live `minisd login` process. A reader thread folds the merged
     * stderr+stdout into a buffer; the steps await markers against it.
     */
    internal class LoginSession(private val proc: Process) {
        // java.lang.Object explicitly: Kotlin's Any hides wait/notifyAll,
        // and the marker await lives on this monitor.
        private val lock: java.lang.Object = java.lang.Object()
        private val buf = StringBuilder()
        @Volatile private var exited = false
        @Volatile private var exitCode = -1
        private val reader = kotlin.concurrent.thread(name = "minisd-login-out", isDaemon = true) {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) {
                        buf.append(line).append('\n')
                        lock.notifyAll()
                    }
                }
            } catch (_: Exception) {
                // Stream torn down by kill() — the exit block below runs.
            }
            synchronized(lock) {
                exited = true
                exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                lock.notifyAll()
            }
        }

        val alive: Boolean get() = !exited

        fun writeLine(line: String): Boolean = runCatching {
            proc.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
            true
        }.getOrDefault(false)

        /** Blocks until a marker shows up, the process exits, or the
         *  deadline passes. */
        fun await(markers: List<String>, timeoutSec: Long): Await {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            synchronized(lock) {
                while (true) {
                    val full = buf.toString()
                    markers.firstOrNull { full.contains(it) }?.let { return Await.Marker(it) }
                    if (exited) return Await.Exit(exitCode)
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return Await.Timeout
                    lock.wait(minOf(left, 250))
                }
            }
        }

        fun tail(): String = synchronized(lock) { buf.toString() }

        fun kill() {
            runCatching { proc.destroy() }
        }

        /** Test/join seam: the reader thread ends after the pipe closes. */
        fun joinReader(millis: Long) { reader.join(millis) }
    }

    @Volatile private var session: LoginSession? = null
    private val sessionLock = Any()

    /**
     * One screen step. Same entry contract as
     * [TelegramLoginRunner.run]; never throws — failures are JSON errors.
     * Negative exit codes: -1 no binary, -3 spawn failure.
     */
    fun run(
        context: Context,
        args: List<String>,
        apiId: String? = null,
        apiHash: String? = null,
        timeoutSec: Long = 90,
    ): MinisdLoginResult = synchronized(sessionLock) {
        val parsed = parseArgs(args)
        try {
            when (parsed.step) {
                "status" -> MinisdLoginResult(0, statusJson(
                    hasGrammers = File(File(context.filesDir, "tg-vault"), "grammers.session").isFile,
                    hasAccounts = TelegramAccountStore(File(context.filesDir, "tg-vault")).load().isNotEmpty(),
                    envReady = envReady(context),
                ))
                "send-code" -> runSendCode(context, parsed, apiId, apiHash, timeoutSec)
                "verify" -> runVerify(context, parsed, timeoutSec)
                else -> MinisdLoginResult(2, "RESULT " + JSONObject().put("error", "unknown step: ${parsed.step}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "step ${parsed.step} failed: ${e.message}")
            MinisdLoginResult(-3, "RESULT " + JSONObject().put("error", "login step failed: ${e.message}"))
        }
    }

    private fun runSendCode(
        context: Context,
        parsed: StepArgs,
        apiId: String?,
        apiHash: String?,
        timeoutSec: Long,
    ): MinisdLoginResult {
        val phone = parsed.phone?.trim().orEmpty()
        if (phone.isBlank()) {
            return MinisdLoginResult(2, "RESULT " + JSONObject().put("error", "phone is required"))
        }
        // A new phone = a new flow: the old session's code token is void.
        session?.kill()
        session = null
        val fresh = spawnSession(context, apiId, apiHash)
            ?: return MinisdLoginResult(-1, "RESULT " + JSONObject().put(
                "error", "libminisd.so not available — the daemon binary is missing"))
        session = fresh
        // The pipe buffers the line until the daemon's first read — no
        // prompt race with the MTProto connect happening in between.
        fresh.writeLine(phone)
        val outcome = fresh.await(listOf(M_CODE, M_ALREADY), timeoutSec)
        return finishStep("send-code", PH_AWAIT_CODE, fresh, outcome)
    }

    private fun runVerify(context: Context, parsed: StepArgs, timeoutSec: Long): MinisdLoginResult {
        val current = session
        if (current == null || !current.alive) {
            session = null
            return MinisdLoginResult(-1, "RESULT " + JSONObject().put(
                "error", "login session is gone — tap Send code to start over"))
        }
        val code = parsed.code?.trim().orEmpty()
        val password = parsed.password?.trim().orEmpty()
        // The interactive session consumed the code at the FIRST verify;
        // the password follow-up writes ONLY the password (the daemon is
        // at the password prompt — a code line would be read as one).
        if (password.isNotBlank() && currentPhase == PH_AWAIT_PASSWORD) {
            current.writeLine(password)
            val outcome = current.await(listOf(M_SAVED), timeoutSec)
            return finishStep("verify-pw", PH_AWAIT_PASSWORD, current, outcome)
        }
        if (currentPhase == PH_AWAIT_PASSWORD) {
            return MinisdLoginResult(2, "RESULT " + JSONObject().put(
                "error", "the code was accepted — enter the 2FA password"))
        }
        if (code.isBlank()) {
            return MinisdLoginResult(2, "RESULT " + JSONObject().put("error", "code is required"))
        }
        current.writeLine(code)
        val outcome = current.await(listOf(M_PASSWORD, M_SAVED), timeoutSec)
        return finishStep("verify", PH_AWAIT_CODE, current, outcome)
    }

    /** Where the current session sits; gone session = done. */
    private val currentPhase: String
        get() = when {
            session == null -> PH_DONE
            else -> sessionPhase
        }

    // The phase lives on the object: one login at a time, and the screen
    // serializes its steps behind `busy`. Guarded by sessionLock.
    @Volatile private var sessionPhase: String = PH_DONE

    private fun finishStep(step: String, phaseBefore: String, s: LoginSession, outcome: Await): MinisdLoginResult {
        val verdict = verdictFor(step, phaseBefore, outcome, s.tail())
        sessionPhase = verdict.phase
        if (verdict.phase == PH_DONE) {
            if (outcome is Await.Exit && outcome.code != 0 || outcome is Await.Timeout) {
                s.kill()
            }
            session = null
        }
        val exit = (outcome as? Await.Exit)?.code ?: 0
        return MinisdLoginResult(exit, "RESULT ${verdict.json}")
    }

    private fun spawnSession(context: Context, apiId: String?, apiHash: String?): LoginSession? {
        val bin = File(context.applicationInfo.nativeLibraryDir, DaemonSupervisor.BINARY_NAME)
        if (!bin.isFile) return null
        return try {
            val pb = ProcessBuilder(
                listOf(bin.absolutePath, "login", "--storage",
                    File(context.filesDir, "tg-vault").absolutePath),
            ).apply { redirectErrorStream(true) }
            val env = pb.environment()
            // Screen-typed credentials win — the same contract as the
            // python driver; otherwise the encrypted env-var store.
            apiId?.takeIf { it.isNotBlank() }?.let { env["TG_API_ID"] = it }
                ?: envValue(context, "TG_API_ID")?.let { env["TG_API_ID"] = it }
            apiHash?.takeIf { it.isNotBlank() }?.let { env["TG_API_HASH"] = it }
                ?: envValue(context, "TG_API_HASH")?.let { env["TG_API_HASH"] = it }
            LoginSession(pb.start())
        } catch (e: Exception) {
            Log.e(TAG, "login spawn failed: ${e.message}")
            null
        }
    }

    private fun envReady(context: Context): Boolean =
        !envValue(context, "TG_API_ID").isNullOrBlank() &&
            !envValue(context, "TG_API_HASH").isNullOrBlank()

    private fun envValue(context: Context, key: String): String? = try {
        EnvVarRepository(context).getValue(key)
    } catch (e: Exception) {
        Log.w(TAG, "env-var read failed for $key: ${e.message}")
        null
    }
}
