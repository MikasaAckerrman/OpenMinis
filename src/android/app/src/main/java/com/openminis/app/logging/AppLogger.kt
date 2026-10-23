package com.openminis.app.logging

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Daily-rotating file logger that mirrors iOS LoggingManager.
 * Writes log entries to files named yyyy-MM-dd.log in app's files/logs/ directory.
 * Retains logs for 14 days by default.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    // [T-android-log-retention-15d] 15 days, matching iOS logRetentionDays
    // (d83bc894). The previous 14 came from the March parity-scaffolding
    // batch with no recorded rationale — plain historical drift, not intent.
    private const val MAX_AGE_DAYS = 15
    /**
     * SimpleDateFormat is NOT thread-safe. Logging runs concurrently from the
     * UI thread, several coroutine dispatchers and the LogcatTailer sink
     * thread, and the three writers below shared ONE formatter instance, so a
     * concurrent format could emit a torn date (or throw). writeFileLine was
     * already @Synchronized; log() and writeLogcatLine() were not.
     *
     * [T-gc-churn] One formatter set per thread, created once: the hot path
     * no longer allocates a formatter per line, and no longer formats the
     * day label twice per line (the day label is cached until the epoch day
     * rolls over, so only the millisecond stamp is rebuilt).
     */
    internal class Formatters(
        private val zone: TimeZone = TimeZone.getDefault(),
    ) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
        /**
         * Local-timezone calendar, used ONLY to derive the day key.
         *
         * The day key MUST be a local day: the label is rendered by [day],
         * which formats in the device's timezone. Keying the cache on
         * `millis / 86_400_000` (UTC days) invalidated it at UTC midnight,
         * so between local midnight and UTC midnight the cached label was
         * YESTERDAY's date — and since getWriter() names the file
         * `minis-$date.log`, log lines landed in the wrong daily file.
         * At UTC+4 that window is 4 hours a day; at negative offsets up to
         * 19 hours.
         *
         * The calendar and both formatters share [zone], so the key and the
         * label can never disagree about which day it is.
         */
        private val cal = Calendar.getInstance(zone)
        @Volatile var dayKey: Long = Long.MIN_VALUE
        @Volatile var dayLabel: String = ""

        /** Day label for [millis], formatted only when the day changed. */
        fun dayLabel(millis: Long): String {
            cal.timeInMillis = millis
            // year * 1000 + dayOfYear is unique per year (dayOfYear <= 366).
            val key = cal.get(Calendar.YEAR).toLong() * 1000L +
                cal.get(Calendar.DAY_OF_YEAR).toLong()
            if (key != dayKey) {
                synchronized(this) {
                    if (key != dayKey) {
                        dayLabel = day.format(Date(millis))
                        dayKey = key
                    }
                }
            }
            return dayLabel
        }
    }

    private val formattersTls = object : ThreadLocal<Formatters>() {
        override fun initialValue(): Formatters = Formatters()
    }

    private const val PREF_NAME = "logging_prefs"
    private const val KEY_ENABLED = "logging_enabled"

    private var logDir: File? = null
    private var currentDate: String = ""
    private var writer: PrintWriter? = null
    private var enabled: Boolean = false

    /**
     * [T-logging-gate-before-logcat] Whether `log()` forwards to logcat.
     *
     * Kept apart from [enabled] (the file switch) so the two costs can be
     * paid separately: the file is what the user asked for, logcat is what
     * a developer needs from a debug build. Defaults from the build type —
     * debug keeps `adb logcat` usable with the file switch off, release
     * stays silent — and follows [enabled] once the user touches the
     * toggle, because someone who turned logging ON expects both sinks.
     */
    @Volatile
    private var logcatEnabled: Boolean = com.openminis.app.BuildConfig.DEBUG

    /**
     * Flush policy shared by ALL writers (log(), writeFileLine(),
     * writeLogcatLine()).
     *
     * `PrintWriter.flush()` is a synchronous I/O round trip on the calling
     * thread. Doing it per line turned every captured stdout line and every
     * logcat tail line into one disk round trip — in the 19.10 incident
     * `System.out` alone produced 14 927 lines, and the logcat tail carries
     * tens of thousands more. The bounded policy keeps the data on disk
     * within one flush interval while removing the per-line cost.
     *
     * The counter is atomic because the three writers run on different
     * threads (UI, coroutine dispatchers, the logcat tailer) and a plain
     * int would lose increments.
     */
    private val pendingWrites = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastFlushMs: Long = 0L

    /**
     * logcat tags ("Minis.<category>"), built once per category.
     *
     * Bounded: the key is always a compile-time constant from the call
     * sites, never a runtime string, so the map cannot grow with traffic.
     * Concurrent because log() runs on the UI thread, coroutine dispatchers
     * and the logcat tailer at once.
     */
    private val logcatTags = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Saved references to the JVM's original stdout/stderr. Captured on the
    // first startCapture() so stopCapture() can restore them — without this we
    // would never be able to detach our PrintStream wrapper, and the redirection
    // would survive the toggle being flipped off.
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var captureActive: Boolean = false

    // Logcat tail child process — captures Log.d/i/w/e/v from the framework,
    // third-party libraries, and project code that calls android.util.Log
    // directly. Without this only stdout/stderr (println, stack traces) end
    // up in the file, which is < 1% of the actual log volume on Android.
    private var logcatTailer: LogcatTailer? = null

    /**
     * Initialize the logger with app context. Call once from Application.onCreate().
     * If logging was previously enabled (persisted in SharedPreferences),
     * automatically begins capturing stdout/stderr — mirrors iOS
     * `LoggingManager.startIfEnabled()`.
     */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        enabled = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        // Mirror the persisted state into the perf tracer at startup, otherwise
        // a user who left logging ON would restart with the breadcrumbs silently
        // off (and vice versa the tracer would stay on after a disable+restart).
        com.openminis.app.diagnostics.PerfLongCtx.setEnabled(enabled)
        pruneOldLogs()
        if (enabled) startCapture()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        // [T-logging-gate-before-logcat] Turning the switch ON means "I want
        // diagnostics", which reads as both sinks; turning it OFF must silence
        // both. Before this, the logcat side ignored the switch entirely.
        logcatEnabled = value || com.openminis.app.BuildConfig.DEBUG
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        // [T-android-perf-diag-costs-perf] The perf breadcrumb stream rides the
        // same switch: it is only useful while someone is diagnosing, and its
        // emit path (native-heap read + a Log.i per step) is too expensive to
        // leave on for every user. Single toggle, so the two can never disagree.
        com.openminis.app.diagnostics.PerfLongCtx.setEnabled(value)
        if (value) startCapture() else stopCapture()
    }

    /**
     * Redirect [System.out] and [System.err] through line-buffered writers
     * that prepend a timestamp to each line and append it to today's log file
     * before forwarding the original bytes to the previous stream. The forward
     * is essential — without it, anything written through stdout (println,
     * Throwable.printStackTrace, third-party libs that write to System.err)
     * would silently disappear from logcat.
     *
     * `Log.d/i/w/e` calls go through the Android native logging bridge and
     * are NOT captured by this redirection — only stdout/stderr.
     */
    @Synchronized
    private fun startCapture() {
        if (captureActive) return
        if (logDir == null) return // init() not called yet
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err
        System.setOut(PrintStream(LineCapturingStream(originalOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineCapturingStream(originalErr!!, "STDERR"), true))
        // Spawn the logcat tail before emitting the session-start marker so
        // the marker itself shows up in the captured stream as a sanity check.
        logcatTailer = LogcatTailer { line -> writeLogcatLine(line) }.also { it.start() }
        captureActive = true
        info("AppLogger", "Logging session started — capturing stdout/stderr + logcat tail")
    }

    @Synchronized
    private fun stopCapture() {
        if (!captureActive) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        logcatTailer?.stop()
        logcatTailer = null
        captureActive = false
        // Close the daily writer so any buffered bytes are flushed; getWriter()
        // will reopen on the next file write.
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        currentDate = ""
    }

    /**
     * Tags whose lines AppLogger has ALREADY written to the file itself, so
     * the logcat tail must not write them a second time.
     *
     * - `Minis.*` and `AppLogger`: emitted by [log] through [writer].
     * - `System.out` / `System.err`: [install] replaced the JVM's stdout and
     *   stderr with a [PrintWriter] that writes each captured line via
     *   [writeFileLine] AND forwards the raw bytes to the original stream.
     *   Those forwarded bytes reach logcat and come back through
     *   [LogcatTailer] under the `System.out` tag — so without this filter
     *   every `println` in the process was written to the file TWICE, with a
     *   `flush()` each time, plus one String allocation per line on the
     *   tailer's `readLine()`. In the 19.10 incident `System.out` alone
     *   accounted for 14 927 log lines.
     *
     * The only loss is a line printed to stdout BEFORE [install] ran; the
     * tailer starts with `-T 1` (most recent line only) and was never a
     * reliable source for history anyway.
     */
    internal fun isOwnOutputTag(tag: String): Boolean =
        tag.startsWith("Minis.") || tag == "AppLogger" ||
            tag == "System.out" || tag == "System.err"

    /**
     * Append a logcat tail line to today's log file.
     *
     * Lines AppLogger itself produced are skipped — [log] already wrote them
     * via the file writer, so without this filter every `info()` /
     * `warning()` call appeared twice (once from [log], once echoed back
     * through logcat). The same applies to `System.out` / `System.err`,
     * which [install] captures into the file itself.
     */
    private fun writeLogcatLine(rawLine: String) {
        if (!enabled) return
        // logcat -v time format: "MM-DD HH:MM:SS.mmm L/Tag(pid): message"
        // Extract the tag to filter our own output.
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx >= 0 && parenIdx > slashIdx) {
            val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
            if (isOwnOutputTag(tag)) return
        }
        try {
            val now = Date()
            val nowMs = now.time
            val today = formattersTls.get()!!.dayLabel(nowMs)
            val w = getWriter(today)
            w.println("[LOGCAT] $rawLine")
            // [T-logging-hot-path] Bounded flush, same as the other writers:
            // the tail carries every framework log line, so a flush per line
            // was tens of thousands of disk round trips over a long session.
            maybeFlush(w, nowMs)
        } catch (_: Exception) {
            // Swallow — must not feed back into logcat or we loop forever.
        }
    }

    /**
     * OutputStream wrapper that:
     *   1. Forwards every byte to [delegate] (the original stdout/stderr) so
     *      logcat / adb still receives the output unchanged.
     *   2. Buffers bytes into [buffer] until a `\n` arrives, then writes the
     *      complete line — prefixed with `[HH:mm:ss.SSS] [LEVEL] [tag]` — to
     *      the daily log file. Partial lines are flushed on close().
     */
    // [T-test] internal rather than private so a unit test can drive the
    // capture buffer directly - the truncation cap and the close() flush are
    // impossible to reach through System.out without spawning a process.
    internal class LineCapturingStream(
        private val delegate: PrintStream,
        private val tag: String,
        // [T-test] Indirection so a unit test can observe the emitted lines
        // (and the truncation marker) without AppLogger being initialised -
        // writeFileLine needs filesDir and enabled, neither of which exist in
        // a plain JVM test.
        private val onLine: (String) -> Unit = { writeFileLine(tag, it) },
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream(256)
        private var overflowed = false

        override fun write(b: Int) {
            // Always forward first; any failure to capture must NOT swallow output.
            delegate.write(b)
            if (b == '\n'.code) {
                emitLine()
            } else if (buffer.size() < MAX_CAPTURED_BYTES) {
                buffer.write(b)
            } else {
                overflowed = true
            }
        }

        /**
         * Append `b[from, until)` unless the per-line cap is reached.
         *
         * [STDOUT-CAPTURE] The buffer drains only when a newline arrives, so a
         * writer that emits none grows it without bound — and
         * ByteArrayOutputStream doubles its backing array on every overflow,
         * so newline-free output from a child process is an
         * OutOfMemoryError. The line is cut at the cap and the truncation is
         * reported, so the reader sees that something was dropped instead of
         * an unexplained gap.
         */
        private fun appendRange(b: ByteArray, from: Int, until: Int) {
            if (from >= until) return
            val room = MAX_CAPTURED_BYTES - buffer.size()
            if (room <= 0) {
                overflowed = true
                return
            }
            val n = minOf(until - from, room)
            buffer.write(b, from, n)
            if (n < until - from) overflowed = true
        }

        private companion object {
            /** 64 KB per unterminated line — far above any real log line. */
            const val MAX_CAPTURED_BYTES = 64 * 1024
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            var lineStart = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) {
                    appendRange(b, lineStart, i)
                    emitLine()
                    lineStart = i + 1
                }
            }
            appendRange(b, lineStart, end)
        }

        override fun flush() {
            delegate.flush()
        }

        /**
         * Flush whatever is left, then close the delegate.
         *
         * The previous version had no `close()` at all while its KDoc
         * promised "Partial lines are flushed on close()", so a program that
         * ended without a trailing newline lost that line for good.
         */
        override fun close() {
            emitLine()
            try {
                delegate.flush()
            } catch (_: Throwable) {
                // Nothing useful to do; close must never throw into the caller.
            }
        }

        private fun emitLine() {
            var line = try {
                buffer.toString("UTF-8")
            } catch (_: Exception) {
                buffer.toString()
            }
            buffer.reset()
            if (overflowed) {
                line += "…[line truncated at $MAX_CAPTURED_BYTES bytes]"
                overflowed = false
            }
            // Drop empty lines so the file isn't full of bare timestamps.
            if (line.isEmpty()) return
            onLine(line)
        }
    }

    /**
     * Append a captured stdout/stderr line to today's log file. Catches all
     * I/O failures so a flaky filesystem can't crash the app's stdout.
     */
    @Synchronized
    private fun writeFileLine(channel: String, line: String) {
        if (!enabled) return
        try {
            val now = Date()
            val nowMs = now.time
            val f = formattersTls.get()!!
            val today = f.dayLabel(nowMs)
            val timestamp = f.stamp.format(now)
            val w = getWriter(today)
            w.println("[$timestamp] [$channel] $line")
            // [T-logging-hot-path] Same bounded policy as log(): the
            // captured-stdout path produced 14 927 lines in the 19.10
            // incident, and a flush per line was one disk round trip each.
            maybeFlush(w, nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write captured line: ${e.message}")
        }
    }

    /**
     * Log a message at INFO level with a category tag.
     */
    fun info(category: String, message: String) {
        log("INFO", category, message)
    }

    /**
     * [T-sse-debug-hot-path] Cheap gate for HIGH-FREQUENCY debug logs (per
     * streamed token, per scroll frame). When file logging is off (default),
     * such a log costs a string build + Log.d on the COLLECTING thread —
     * which for SSE is the UI thread, dozens of times per second while a
     * response streams. Hot-path diagnostics must check this flag and skip
     * entirely; cold per-request logs don't need to.
     */
    val isDebugEnabled: Boolean
        get() = enabled

    fun warning(category: String, message: String) {
        log("WARN", category, message)
    }

    fun error(category: String, message: String) {
        log("ERROR", category, message)
    }

    /**
     * Categories whose DEBUG logs we silently drop. Useful for high-volume
     * categories that fire on every scroll frame / token (e.g. the chat
     * scroll-follow path under tag "ChatScrollFollow") — gating at the
     * caller would require touching dozens of sites; gating here keeps the
     * Log.d + file-write cost off the hot path. The string formatting at
     * each caller still pays for itself (we can't fix that without lambdas)
     * but `Log.d → liblog → LogcatTailer → file write` is more expensive
     * than the string build alone.
     */
    private val mutedDebugCategories = setOf("ChatScrollFollow")

    fun debug(category: String, message: String) {
        if (category in mutedDebugCategories) return
        log("DEBUG", category, message)
    }

    private fun log(level: String, category: String, message: String) {
        // [T-logging-gate-before-logcat] The gate used to sit BELOW the
        // `when (level)` block, so `Log.*` fired on every call while the
        // user-facing "logging" switch only suppressed the FILE. Evidence
        // from a real device with `logging_enabled=false`: 61 545 lines
        // under `Minis.*` tags in logcat (ChatViewModel 30 417,
        // OkHttpNetTrace 16 099, OpenAIProvider 5 854) — all of them paid
        // for, and none of them visible to the user who turned logging off.
        // The string itself is built by the CALLER before this function is
        // entered, so the real saving at the hot call sites comes from
        // guarding them with [isDebugEnabled] (see ChatViewModel), but this
        // gate stops the native write that no caller can avoid.
        //
        // `logcatEnabled` is separate from `enabled` on purpose: dropping
        // logcat output outright would delete the only diagnostics channel
        // when file logging is off. It defaults to the build type, so a
        // debug build keeps `adb logcat` usable while a release build stays
        // silent unless logging is explicitly switched on.
        if (!enabled && !logcatEnabled) return

        // [T-logging-hot-path] The tag was rebuilt as "Minis.$category" on
        // every call. ChatViewModel alone issues 265 log calls, several of
        // them per streamed token, so that concatenation was one String per
        // call for the whole session. The categories are code constants, so
        // the map stays bounded.
        if (logcatEnabled) {
            val logcatTag = logcatTags.getOrPut(category) { "Minis.$category" }
            when (level) {
                "ERROR" -> Log.e(logcatTag, message)
                "WARN" -> Log.w(logcatTag, message)
                "DEBUG" -> Log.d(logcatTag, message)
                else -> Log.i(logcatTag, message)
            }
        }

        // Write to file (only if enabled)
        if (!enabled) return
        // [T-logging-hot-path-gate] formattersTls.get() is only needed to
        // stamp the FILE line, so it is fetched after both gates instead of
        // before them — one ThreadLocal lookup saved per suppressed call.
        val f = formattersTls.get()!!
        try {
            // [T-logging-hot-path] The timestamp and the day label are only
            // needed for the FILE line. Building them before the enabled
            // check formatted a timestamp on every log call even when file
            // logging was off — the default state — and the app issues
            // thousands of log calls per session.
            val now = Date()
            val nowMs = now.time
            val today = f.dayLabel(nowMs)
            val timestamp = f.stamp.format(now)
            val w = getWriter(today)
            w.println("[$timestamp] [$level] [$category] $message")
            // [T-logging-hot-path] println() is buffered by the Writer; a
            // flush() per entry turned every log call into a synchronous I/O
            // round-trip ON THE CALLING THREAD — dozens per second while a
            // response streams with logging enabled, which is the constant
            // lag floor the user felt on long sessions. Flush is bounded by
            // count AND age instead: worst case loses the last second of
            // entries on a crash — acceptable for diagnostics.
            maybeFlush(w, nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write log: ${e.message}")
        }
    }

    /** Flush [w] if enough entries piled up or the last flush is too old. */
    private fun maybeFlush(w: PrintWriter, nowMs: Long) {
        val n = pendingWrites.incrementAndGet()
        if (n >= FLUSH_EVERY || nowMs - lastFlushMs >= FLUSH_INTERVAL_MS) {
            w.flush()
            pendingWrites.set(0)
            lastFlushMs = nowMs
        }
    }

    private const val FLUSH_EVERY = 32
    private const val FLUSH_INTERVAL_MS = 1000L

    @Synchronized
    private fun getWriter(date: String): PrintWriter {
        // [T-applogger-getwriter] Fast path must not re-stamp the writers.
        // Two threads logging for the SAME day must get the SAME instance:
        // printing into a PrintWriter that another thread is closing
        // interleaves output, and closing drops whatever is still buffered
        // in that PrintWriter - which is the only copy of those lines.
        val existing = writer
        if (existing != null && date == currentDate) return existing

        // Publish nothing until the replacement exists. The previous code
        // closed the old writer and left it in `writer`, so a failing
        // FileWriter (no space, missing dir, `logDir` still null in
        // safe-mode) left the field pointing at a CLOSED writer while
        // currentDate stayed stale. Every later call then re-ran the same
        // failing branch: silent log loss plus a Log.w per line until the
        // next day rolled over. With the field cleared first, a failure
        // leaves writer == null and the next call simply retries.
        writer = null
        try {
            existing?.close()
        } catch (_: Throwable) {
            // A PrintWriter swallows IOException, but never let a close()
            // failure block the rollover - the data is unrecoverable either
            // way and the new file is what matters.
        }
        val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
        val file = File(dir, "minis-$date.log")
        val fresh = PrintWriter(FileWriter(file, true))
        writer = fresh
        currentDate = date
        return fresh
    }

    /**
     * List available log files, newest first.
     */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Lightweight metadata for a single log file. Captures name + size +
     * mtime ONCE so the UI never re-stat's the file during recomposition
     * (LogManagementScreen used to call file.length() per row per
     * recomposition, which scaled badly once dozens of crash files piled
     * up alongside the daily logs).
     */
    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * Capped, prefix-filtered log listing for the UI.
     *
     * - `prefix`: filename starts-with filter (e.g. `"minis-"` for daily
     *   logs, `"crash-"` / `"native-crash-"` for crash reports). Empty
     *   string returns all `.log` files.
     * - `limit`: keep at most this many files, sorted by name descending
     *   (newest first, since both daily and crash filenames embed
     *   YYYY-MM-DD prefixes that sort correctly).
     *
     * Captures size + mtime per file via a single `stat` per entry, so a
     * caller iterating the result list never has to re-stat. Total size
     * is computed by the caller (sum of `sizeBytes`) — no second
     * directory walk needed.
     *
     * Pure data; safe to call from `Dispatchers.IO`.
     */
    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        // Sort then cap BEFORE the per-file stat — File.listFiles already
        // populated name internally, but length()/lastModified() are
        // separate stat syscalls we'd rather skip on the tail.
        return files
            .sortedByDescending { it.name }
            .take(limit)
            .map { LogFileMeta(it.name, it.length(), it.lastModified()) }
    }

    /**
     * Read content of a specific log file.
     */
    fun readLog(filename: String): String? {
        val dir = logDir ?: return null
        val file = File(dir, filename)
        return if (file.exists()) file.readText() else null
    }

    /**
     * Delete all log files.
     *
     * [T-mutation-journal] The append-only journals are exempt, for the same
     * reason they are exempt from [pruneOldLogs]: "Clear Logs" is a housekeeping
     * gesture aimed at the noisy daily captures, and a user tapping it must not
     * silently destroy the record of every deletion in the app's history. The
     * journal caps itself by size, so it never becomes the reason someone needs
     * to clear logs.
     */
    @Synchronized
    fun clearLogs() {
        logDir?.listFiles()?.forEach { if (it.name !in PRUNE_EXEMPT) it.delete() }
        // [T-logging-zombie-fd-android] The open writer still references the
        // just-deleted file; a FileWriter on an unlinked inode keeps writing to
        // the zombie file (invisible on disk) until currentDate changes or the
        // writer is nulled. Drop it and reset currentDate so the next
        // getWriter() reopens a fresh minis-<date>.log on the following write.
        // @Synchronized shares getWriter()'s monitor so this can't race a write.
        writer?.close()
        writer = null
        currentDate = ""
    }

    /**
     * Total size of all log files in bytes.
     */
    fun totalSize(): Long {
        return logDir?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * [T-mutation-journal] Files in `logs/` that pruning must NEVER touch.
     *
     * The prune below deletes anything older than [MAX_AGE_DAYS], which is right
     * for daily log files and catastrophic for the append-only journals: after 15
     * quiet days with no destructive operation, `mutation-journal.log` would be
     * deleted — taking the record of every earlier deletion with it. That is
     * exactly the failure the journal exists to prevent, and it is the same shape
     * as the incident (the evidence aged out before anyone looked).
     *
     * These files manage their own size instead: MutationJournal and
     * NetworkJournal trim by bytes, LaunchCycleBeacon's entries are one tiny line
     * per launch.
     */
    private val PRUNE_EXEMPT = setOf(
        "mutation-journal.log",
        "network-journal.log",
        "launch-beacon.log",
    )

    private fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        logDir?.listFiles()?.forEach { file ->
            if (file.name in PRUNE_EXEMPT) return@forEach
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
