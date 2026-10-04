package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-bg-tasks] Background process tools — the ZCode-class capability the
 * platform lacked: start a long-running command NOW, get a task id, keep
 * working, and read the output LATER ("start a server, keep working, check
 * the logs").
 *
 * Architecture:
 * - The registry is PROCESS-GLOBAL (SupervisorJob scope) — tasks survive
 *   session switches and VM recreation, and die with the app process
 *   (their children are PRoot processes of this very process).
 * - Output streams to /var/minis/workspace/.bg/<taskId>.log — readable by
 *   file_read at any time (and after app death the log file survives).
 * - Killing goes through the coroutine Job — ExecutionCoordinator's
 *   cancellation path performs the async shell kill (the same machinery
 *   the STOP button uses).
 *
 * Honest limits (also in the tool descriptions for the model):
 * - While the app is frozen by the OEM the reader coroutine pauses; the
 *   child keeps writing — output catches up on unfreeze.
 * - After the app process dies the registry is lost; orphaned children
 *   may keep running (same as the supermemory daemon pattern) — the log
 *   file on disk is the source of truth then.
 */
object BgTaskTools {

    const val BG_RUN_NAME = "bg_run"
    const val BG_CHECK_NAME = "bg_check"
    const val BG_LIST_NAME = "bg_list"
    const val BG_KILL_NAME = "bg_kill"

    private const val LOG_DIR = "/var/minis/workspace/.bg"
    private const val DEFAULT_TIMEOUT_SEC = 1800L
    private const val MAX_TIMEOUT_SEC = 43200L
    private const val MAX_TAIL_LINES = 200

    private val seq = AtomicInteger(1)

    class Task(
        val id: String,
        val sessionId: String,
        val command: String,
        val startedAtMs: Long,
        @Volatile var state: String, // RUNNING | DONE | FAILED | TIMEOUT | KILLED
        @Volatile var exitCode: Int? = null,
        val logFile: File,
        @Volatile var job: Job? = null,
    )

    // Process-global registry: outlives any single ChatViewModel instance.
    private val tasks = ConcurrentHashMap<String, Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun definitions(): List<AgentToolDefinition> = listOf(
        bgRunDefinition(), bgCheckDefinition(), bgListDefinition(), bgKillDefinition(),
    )

    private fun bgRunDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = BG_RUN_NAME,
        description = "Run a shell command in the BACKGROUND and return a task_id " +
            "immediately (does not block the conversation). Use for long-running work: " +
            "servers, test suites, builds, watchers, daemons. The command keeps running " +
            "while you (the agent) continue the conversation; read its output later " +
            "with bg_check. Output is also teed to /var/minis/workspace/.bg/<task_id>.log " +
            "(survives app restarts — readable with file_read). IMPORTANT: the command " +
            "must redirect its own stdout/stderr sensibly if it is a daemon (e.g. " +
            "'python3 -m http.server 8765 > /dev/null 2>&1' style apps still get tee'd " +
            "here; plain daemons that daemonize themselves may exit immediately — " +
            "that is a normal DONE exit 0). Destructive commands (rm with globs/repos) " +
            "are refused — use foreground shell_execute for anything needing " +
            "confirmation.",
        parameters = mapOf(
            "command" to AgentToolParam(
                type = "string",
                description = "The shell command to run detached. Runs with the same " +
                    "sandbox and permissions as shell_execute.",
            ),
            "timeout" to AgentToolParam(
                type = "integer",
                description = "Kill the task after this many seconds (default 1800, " +
                    "max 43200 = 12h).",
            ),
            "label" to AgentToolParam(
                type = "string",
                description = "Short human label for bg_list readability (optional).",
            ),
        ),
        required = listOf("command"),
    )

    private fun bgCheckDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = BG_CHECK_NAME,
        description = "Check a background task started with bg_run: state " +
            "(RUNNING/DONE/FAILED/TIMEOUT/KILLED), exit code, runtime, and the tail " +
            "of its output. Call this after starting work, or anytime later — a " +
            "RUNNING task means keep doing other things and check again.",
        parameters = mapOf(
            "task_id" to AgentToolParam(
                type = "string", description = "Task id from bg_run.",
            ),
            "lines" to AgentToolParam(
                type = "integer",
                description = "How many tail lines of output to return (default 40, max 200).",
            ),
        ),
        required = listOf("task_id"),
    )

    private fun bgListDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = BG_LIST_NAME,
        description = "List all background tasks of this app process (id, label, " +
            "state, runtime, command). Use it when you forgot a task id.",
        parameters = emptyMap(),
    )

    private fun bgKillDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = BG_KILL_NAME,
        description = "Stop a background task by id. The shell kill is asynchronous " +
            "(returns immediately); state becomes KILLED. Safe to call on an already " +
            "finished task (no-op).",
        parameters = mapOf(
            "task_id" to AgentToolParam(
                type = "string", description = "Task id from bg_run.",
            ),
        ),
        required = listOf("task_id"),
    )

    // ------------------------------------------------------------------
    // Registry access — called from the ChatViewModel dispatch.
    // ------------------------------------------------------------------

    fun nextId(label: String?): String {
        val n = seq.getAndIncrement()
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val l = label?.trim()?.take(12)?.replace(Regex("[^\\w.-]"), "")?.let { "-$it" } ?: ""
        return "t$stamp-$n$l"
    }

    fun logFileFor(taskId: String): File =
        File(LOG_DIR, "$taskId.log").apply { parentFile.mkdirs() }

    fun register(task: Task) {
        tasks[task.id] = task
    }

    fun get(taskId: String): Task? = tasks[taskId]

    fun all(): List<Task> = tasks.values.sortedByDescending { it.startedAtMs }

    /**
     * Launch helper: the ChatViewModel supplies the actual execution block
     * (ExecutionCoordinator + policy gates live there); this wraps it in a
     * supervised coroutine, wires the registry state transitions, and tees
     * every output line into the task log file. Returns immediately.
     */
    fun launchDetached(
        sessionId: String,
        command: String,
        label: String?,
        timeoutSec: Long,
        exec: suspend (appendLine: (String) -> Unit) -> Pair<Int, String>, // (exitCode, output)
    ): Task {
        val id = nextId(label)
        val log = logFileFor(id)
        val task = Task(
            id = id, sessionId = sessionId, command = command,
            startedAtMs = System.currentTimeMillis(), state = "RUNNING", logFile = log,
        )
        val sb = StringBuilder()
        val started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("[$started] bg_run: $command\n")
        val appendLine: (String) -> Unit = { line ->
            synchronized(sb) {
                sb.append(line).append('\n')
                runCatching { log.appendText(line + "\n") }
            }
        }
        task.job = scope.launch {
            try {
                val (exit, _) = exec(appendLine)
                task.exitCode = exit
                task.state = when {
                    exit == 124 -> "TIMEOUT"
                    exit == 0 -> "DONE"
                    else -> "FAILED"
                }
                appendLine("[bg] finished: exit=$exit state=${task.state}")
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // bg_kill path: the KILLED state was already set by kill()
                // — do NOT overwrite it. runCatching would swallow the
                // cancellation and mislabel the task FAILED.
                if (task.state != "KILLED") task.state = "KILLED"
                throw ce
            } catch (t: Throwable) {
                task.exitCode = -1
                task.state = "FAILED"
                runCatching { appendLine("[bg] crashed: ${t.message}") }
            }
        }
        register(task)
        runCatching { log.appendText("[$started] bg_run: $command\n") }
        return task
    }

    fun kill(taskId: String): Boolean {
        val t = tasks[taskId] ?: return false
        t.job?.cancel()
        t.state = "KILLED"
        return true
    }

    /** Human-facing snapshot for bg_check — pure read, no side effects. */
    fun check(taskId: String, tailLines: Int): Triple<Task?, String, String> {
        val t = tasks[taskId]
        if (t == null) {
            // Registry lost (app restarted) — the log file may still exist.
            val log = logFileFor(taskId)
            val tail = tailOf(log, tailLines)
            return Triple(null, "LOST (task not in registry — app restarted?)", tail)
        }
        val runSec = (System.currentTimeMillis() - t.startedAtMs) / 1000
        val header = "state=${t.state} exit=${t.exitCode ?: "—"} runtime=${runSec}s cmd=${t.command}"
        val tail = tailOf(t.logFile, tailLines)
        return Triple(t, header, tail)
    }

    /**
     * Bounded tail read: a background daemon can log megabytes over a day —
     * readLines() of the whole file is an OOM on a phone. Read at most the
     * last 256KB via RandomAccessFile, then take the requested lines.
     */
    private fun tailOf(log: File, lines: Int): String = runCatching {
        if (!log.exists()) return@runCatching ""
        val cap = 256 * 1024L
        val len = log.length()
        RandomAccessFile(log, "r").use { raf ->
            if (len > cap) raf.seek(len - cap)
            val buf = ByteArray(minOf(len, cap).toInt())
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            // Drop the potentially-cut first line, then the requested tail.
            val body = if (len > cap) text.substringAfter('\n') else text
            body.lines().takeLast(lines).joinToString("\n")
        }
    }.getOrElse { "" }

    // ------------------------------------------------------------------
    // Arg parsing helpers (kept dumb-simple on purpose).
    // ------------------------------------------------------------------

    fun parseRunArgs(argsJson: String): Triple<String, Long, String?>? {
        val args = JSONObject(argsJson)
        val cmd = args.optString("command", "").trim()
        if (cmd.isEmpty()) return null
        val timeout = args.optLong("timeout", DEFAULT_TIMEOUT_SEC)
            .coerceIn(5, MAX_TIMEOUT_SEC)
        return Triple(cmd, timeout, args.optString("label", "").ifBlank { null })
    }

    fun parseCheckArgs(argsJson: String): Pair<String, Int>? {
        val args = JSONObject(argsJson)
        val id = args.optString("task_id", "").trim()
        if (id.isEmpty()) return null
        val lines = args.optInt("lines", 40).coerceIn(1, MAX_TAIL_LINES)
        return Pair(id, lines)
    }
}
