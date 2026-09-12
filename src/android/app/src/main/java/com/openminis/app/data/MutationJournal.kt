package com.openminis.app.data

import android.content.Context
import java.io.File

/**
 * [T-mutation-journal] Always-on, append-only journal of every operation that
 * REMOVES or REWRITES persisted chat rows.
 *
 * ## Why this exists
 *
 * Session 2c7ae861 lost eleven days of history and the post-mortem could not
 * say which tap did it. Not because the code was unclear, but because there was
 * no evidence at all:
 *
 *  - [com.openminis.app.logging.AppLogger] defaults to DISABLED
 *    (`getBoolean(KEY_ENABLED, false)`), so all 211 AppLogger call sites in
 *    ChatViewModel wrote nothing on the user's device. The only files in
 *    `logs/` were 33 crash-*, 16 stall-* and one launch-beacon.log.
 *  - Even enabled, AppLogger rolls daily and prunes at 15 days, and it captures
 *    stdout/stderr + a logcat tail — i.e. it is a debugging firehose, not a
 *    record of destructive events.
 *
 * So the single class of event we most need to explain after the fact — "rows
 * disappeared" — was the one class of event nobody recorded.
 *
 * ## Design constraints, each learned from that failure
 *
 *  - **Always on.** No preference gate. A journal you have to enable BEFORE the
 *    incident is not a journal, it is a wish. The volume is trivial: one short
 *    line per destructive operation, and destructive operations are rare.
 *  - **Append-only, never rolled by date.** The incident was diagnosed 12 days
 *    late; a 15-day daily-rolling log would already have dropped the evidence.
 *    Trimming is by SIZE and drops the OLDEST lines only when the file exceeds
 *    [MAX_BYTES] — so recent history is never sacrificed to make room.
 *  - **Independent of AppLogger.** Own file, own writer, no shared toggle. A
 *    logging subsystem that is off (or broken) must not silence this.
 *  - **Never throws.** A journal failure must not turn into a user-visible
 *    error, so every entry point swallows Throwable.
 *  - **Records REFUSALS too.** "Nothing was deleted because the anchor did not
 *    resolve" is exactly as diagnostic as a deletion, and it is the signal that
 *    tells us the new guards are firing in the field.
 *
 * ## Format
 *
 * One line per event, tab-separated so it greps and splits trivially:
 * ```
 * 2026-08-29T14:03:11.482  DELETE   retry      sess=2c7ae861  keep=612  total=643  removed=31
 * 2026-08-29T14:05:02.119  REFUSE   edit       sess=2c7ae861  keep=-1   total=643  reason=anchor-unresolved
 * ```
 */
object MutationJournal {

    private const val FILE_NAME = "mutation-journal.log"

    /**
     * Hard ceiling for the journal. At ~110 bytes per line this is roughly
     * 19k destructive events — years of normal use — while staying small
     * enough to attach to a bug report.
     */
    internal const val MAX_BYTES: Long = 2L * 1024L * 1024L

    /** How much of the file survives a trim: newest [KEEP_BYTES] bytes. */
    internal const val KEEP_BYTES: Long = 1L * 1024L * 1024L

    @Volatile
    private var dir: File? = null

    /** Call once from Application.onCreate, before any chat work. */
    fun init(context: Context) {
        runCatching {
            dir = File(context.filesDir, "logs").also { it.mkdirs() }
        }
    }

    fun file(): File? = dir?.let { File(it, FILE_NAME) }

    /**
     * A truncation actually happened.
     *
     * @param op which caller ("retry" | "edit" | "rerun" | "retryLast" | …)
     * @param keepCount rows with sort_order < keepCount were kept
     * @param totalRows session row count BEFORE the delete
     * @param removed rows archived+deleted
     */
    fun recordDelete(
        sessionId: String,
        op: String,
        keepCount: Int,
        totalRows: Int,
        removed: Int,
    ) = write("DELETE", op, sessionId, "keep=$keepCount", "total=$totalRows", "removed=$removed")

    /**
     * A truncation was REFUSED — nothing was removed. [reason] should be short
     * and machine-greppable ("anchor-unresolved", "implausible", "chokepoint").
     */
    fun recordRefusal(
        sessionId: String,
        op: String,
        keepCount: Int,
        totalRows: Int,
        reason: String,
    ) = write("REFUSE", op, sessionId, "keep=$keepCount", "total=$totalRows", "reason=$reason")

    /** A row's parts_json was rewritten in place (surgery, rerun trim). */
    fun recordRewrite(
        sessionId: String,
        op: String,
        messageId: String,
        oldLength: Int,
        newLength: Int,
    ) = write("REWRITE", op, sessionId, "msg=${messageId.take(8)}", "len=$oldLength→$newLength")

    /** Whole-session wipe (clearChat) or session delete. */
    fun recordWipe(
        sessionId: String,
        op: String,
        totalRows: Int,
    ) = write("WIPE", op, sessionId, "total=$totalRows")

    /** A compact marker was written — rows are NOT removed, context is folded. */
    fun recordCompact(
        sessionId: String,
        anchorMessageId: String?,
        compactedCount: Int,
        summaryLength: Int,
    ) = write(
        "COMPACT", "compact", sessionId,
        "anchor=${anchorMessageId?.take(8) ?: "nil"}",
        "count=$compactedCount",
        "summaryLen=$summaryLength",
    )

    /** Rows restored from the deleted_messages archive. */
    fun recordRestore(
        sessionId: String,
        restored: Int,
        skipped: Int,
    ) = write("RESTORE", "archive", sessionId, "restored=$restored", "skipped=$skipped")

    // ─── internals ────────────────────────────────────────────────────────

    private fun write(kind: String, op: String, sessionId: String, vararg fields: String) {
        val f = file() ?: return
        try {
            val line = buildString {
                append(timestamp())
                append('\t').append(kind)
                append('\t').append(op)
                append('\t').append("sess=").append(sessionId.take(8))
                for (field in fields) append('\t').append(field)
            }
            synchronized(this) {
                f.appendText(line + "\n")
                trimIfNeeded(f)
            }
        } catch (_: Throwable) {
            // Journalling must never break the operation it is describing.
        }
    }

    /**
     * Drop the oldest lines once the file passes [MAX_BYTES], keeping the
     * newest [KEEP_BYTES]. Deliberately size-based: an age-based policy would
     * have destroyed the evidence for this very incident.
     */
    private fun trimIfNeeded(f: File) {
        try {
            if (f.length() <= MAX_BYTES) return
            val bytes = f.readBytes()
            val from = (bytes.size - KEEP_BYTES).toInt().coerceAtLeast(0)
            // Start at the first line boundary at/after `from` so the file
            // never begins with half a record.
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
