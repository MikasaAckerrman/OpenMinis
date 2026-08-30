package com.openminis.app.data

import android.content.Context
import java.io.File

/**
 * [T-network-journal] Always-on, append-only journal of transient network
 * failures and their retries.
 *
 * ## Why this exists
 *
 * The user's report was "sessions keep stopping with errors, sometimes in the
 * background, and I do not know why". The visible evidence was two strings —
 * `Unable to resolve host "gorouter.app"` and `connection closed` — and nothing
 * about the CONTEXT that decides which of them it is:
 *
 *  - how many sessions were streaming at that moment (a NAT reaps the idle
 *    session's socket while the busy one masks it),
 *  - whether the screen was off (Doze parks the radio),
 *  - whether the retry that followed actually helped,
 *  - whether the failures cluster on one provider host or hit all of them.
 *
 * Without that, every diagnosis is a guess. This file is the same lesson as
 * [MutationJournal], applied to the network: the class of event you must explain
 * after the fact has to be recorded BEFORE the fact, unconditionally.
 *
 * [com.openminis.app.logging.AppLogger] cannot serve this role. It defaults to
 * DISABLED, so on the user's device every one of its call sites wrote nothing;
 * and even enabled it rolls daily and prunes at 15 days, while it captures a
 * logcat firehose that buries a handful of relevant lines.
 *
 * ## Design constraints
 *
 *  - **Always on, no preference gate.** A journal you must enable before the
 *    incident is not a journal.
 *  - **Size-trimmed, never age-trimmed.** Age-based pruning is what would have
 *    destroyed the evidence for the incident that motivated the mutation
 *    journal.
 *  - **Never throws.** Diagnostics must not create the failure they describe.
 *  - **Records the OUTCOME, not just the error.** "Retried and succeeded on
 *    attempt 2" is the line that tells us the budget works; an error-only log
 *    would make every recovered hiccup look like a fatal.
 *
 * ## Format
 *
 * Tab-separated, one line per event:
 * ```
 * 2026-08-30T01:12:03.114  FAIL   OFFLINE     host=gorouter.app  sess=2c7ae861  streams=2  screen=off  net=off  attempt=1/6
 * 2026-08-30T01:12:19.802  RETRY  OFFLINE     host=gorouter.app  sess=2c7ae861  waited=16s  net=on
 * 2026-08-30T01:12:24.550  OK     OFFLINE     host=gorouter.app  sess=2c7ae861  attempts=1
 * 2026-08-30T01:31:44.019  GIVEUP CONNECTION  host=api.openai.com sess=0d47e928 attempts=4  last=connection closed
 * ```
 */
object NetworkJournal {

    private const val FILE_NAME = "network-journal.log"

    /** Same ceiling as [MutationJournal]: small enough to attach to a report. */
    internal const val MAX_BYTES: Long = 2L * 1024L * 1024L

    /** Newest bytes preserved on trim. */
    internal const val KEEP_BYTES: Long = 1L * 1024L * 1024L

    @Volatile
    private var dir: File? = null

    /** Call once from Application.onCreate. */
    fun init(context: Context) {
        runCatching { dir = File(context.filesDir, "logs").also { it.mkdirs() } }
    }

    fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /**
     * Environment at the moment of a failure. Passed in rather than read here so
     * this object stays free of Android dependencies beyond the file path — and
     * so the caller, which already holds these values, cannot disagree with a
     * second reader.
     *
     * Named FailureContext, not Context: a nested `Context` shadows
     * `android.content.Context` for every member of this object, so [init]'s
     * parameter silently became this class and `filesDir` stopped resolving.
     *
     * @param concurrentStreams sessions streaming right now, including this one.
     *   The reason it matters: with several sessions on DIFFERENT hosts, one
     *   busy session's traffic used to mask an idle one whose pooled socket had
     *   already been reaped (see NetworkMonitor's per-host bookkeeping).
     * @param screenOn null when unknown. Doze parks the radio with the screen
     *   off, which is the usual cause of a resolve failure mid-task.
     * @param online what the connectivity mirror reported.
     */
    data class FailureContext(
        val concurrentStreams: Int,
        val screenOn: Boolean?,
        val online: Boolean?,
    )

    /** A transient failure occurred and a retry is (or is not) pending. */
    fun recordFailure(
        sessionId: String,
        host: String?,
        kind: String,
        attempt: Int,
        maxAttempts: Int,
        ctx: FailureContext,
        message: String?,
    ) = write(
        "FAIL", kind, sessionId,
        "host=${host ?: "?"}",
        "streams=${ctx.concurrentStreams}",
        "screen=${boolWord(ctx.screenOn, "on", "off")}",
        "net=${boolWord(ctx.online, "on", "off")}",
        "attempt=$attempt/$maxAttempts",
        "err=${shorten(message)}",
    )

    /**
     * A retry is being issued. [waitedMs] is the real time spent waiting —
     * backoff plus any connectivity await — which is the number that says
     * whether the budget is generous enough.
     */
    fun recordRetry(
        sessionId: String,
        host: String?,
        kind: String,
        attempt: Int,
        waitedMs: Long,
        onlineNow: Boolean?,
        evictedPool: Boolean,
    ) = write(
        "RETRY", kind, sessionId,
        "host=${host ?: "?"}",
        "attempt=$attempt",
        "waited=${waitedMs / 1000}s",
        "net=${boolWord(onlineNow, "on", "off")}",
        "evicted=$evictedPool",
    )

    /** The turn recovered after [attempts] transient failures. */
    fun recordRecovery(
        sessionId: String,
        host: String?,
        kind: String,
        attempts: Int,
    ) = write("OK", kind, sessionId, "host=${host ?: "?"}", "attempts=$attempts")

    /** The retry budget ran out and the turn failed. */
    fun recordGiveUp(
        sessionId: String,
        host: String?,
        kind: String,
        attempts: Int,
        message: String?,
    ) = write(
        "GIVEUP", kind, sessionId,
        "host=${host ?: "?"}",
        "attempts=$attempts",
        "last=${shorten(message)}",
    )

    // ─── internals ────────────────────────────────────────────────────────

    private fun boolWord(v: Boolean?, t: String, f: String): String =
        when (v) { true -> t; false -> f; null -> "?" }

    /**
     * Error text, single-line and bounded. Newlines and tabs would break the
     * TSV shape this file is greppable because of.
     */
    private fun shorten(message: String?): String {
        if (message.isNullOrBlank()) return "-"
        val flat = message.replace('\n', ' ').replace('\t', ' ').trim()
        return if (flat.length <= 120) flat else flat.take(117) + "..."
    }

    private fun write(kind: String, cls: String, sessionId: String, vararg fields: String) {
        val f = file() ?: return
        try {
            val line = buildString {
                append(timestamp())
                append('\t').append(kind)
                append('\t').append(cls)
                append('\t').append("sess=").append(sessionId.take(8))
                for (field in fields) append('\t').append(field)
            }
            synchronized(this) {
                f.appendText(line + "\n")
                trimIfNeeded(f)
            }
        } catch (_: Throwable) {
            // Diagnostics must never break the operation they describe.
        }
    }

    private fun trimIfNeeded(f: File) {
        try {
            if (f.length() <= MAX_BYTES) return
            val bytes = f.readBytes()
            val from = (bytes.size - KEEP_BYTES).toInt().coerceAtLeast(0)
            var start = from
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            if (start < bytes.size) start++
            f.writeBytes(bytes.copyOfRange(start.coerceAtMost(bytes.size), bytes.size))
        } catch (_: Throwable) {
        }
    }

    private fun timestamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getDefault() }
        .format(java.util.Date())
}
