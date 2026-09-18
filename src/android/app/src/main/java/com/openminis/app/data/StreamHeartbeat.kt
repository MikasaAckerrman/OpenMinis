package com.openminis.app.data

import java.io.File

/**
 * [T-partial-turn-durability] Append-only file journal for an in-flight
 * assistant turn — crash insurance for streamed replies.
 *
 * ## Format
 *
 * One file per stream (`<streamId>.streamlog`), a sequence of ESCAPED
 * newline-terminated lines; each line is one appended text delta. Escaping is
 * minimal and self-contained (no JSON dependency, JVM-unit-testable):
 * `\` → `\\`, newline → `\n`, CR → `\r`.
 *
 * A file whose last line lacks the terminating `\n` was cut mid-write by a
 * process death — that partial line is dropped on read (loses at most one
 * heartbeat chunk), never misparsed.
 *
 * ## Why a file and not the DB
 *
 * The journal must not "rape the DB": a heartbeat row rewritten per interval
 * costs O(n²) cumulative writes (SQLite journal + data + index per
 * transaction) on a row that grows to hundreds of KB. These appends are
 * O(delta) with zero Room involvement; the DB receives ONE insert at
 * finalize, exactly like the pre-existing terminal paths.
 *
 * ## Lifecycle
 *
 *  - `appendDelta` while text streams (gated by [StreamDurability]).
 *  - `delete` when the round completes — the authoritative per-round row
 *    replaces the journal.
 *  - `recoverOrphans` at session load: files that survived a process death
 *    are materialized into assistant rows (text + incompleteness marker),
 *    then removed. A file only exists after [StreamDurability.FIRST_WRITE_CHARS]
 *    was crossed, so `minChars` filters race fragments from a cancel that
 *    landed between read and delete.
 *
 * Pure `java.io` — no Android framework, no Room. Unit-tested on the JVM.
 */
object StreamHeartbeat {

    const val DIR_NAME = "stream-heartbeat"
    const val FILE_SUFFIX = ".streamlog"

    /** Journal dir for a session: `<filesDir>/minis-sessions/<sid>/stream-heartbeat`. */
    fun dirFor(filesDir: File, sessionId: String): File =
        File(File(filesDir, "minis-sessions/$sessionId"), DIR_NAME)

    /** Append one escaped delta line. Cheap: open-append-close, a few syscalls. */
    fun appendDelta(dir: File, streamId: String, delta: String) {
        if (delta.isEmpty()) return
        runCatching {
            dir.mkdirs()
            File(dir, streamId + FILE_SUFFIX).appendText(escape(delta) + "\n")
        }
    }

    /**
     * Concatenated journal text for one stream, or null when no journal
     * exists / nothing survived. A truncated final line is dropped.
     */
    fun readText(dir: File, streamId: String): String? {
        val f = File(dir, streamId + FILE_SUFFIX)
        if (!f.exists() || f.length() == 0L) return null
        val lines = runCatching { f.readText().split('\n') }.getOrNull() ?: return null
        if (lines.size <= 1) return null // "" only — no complete line
        val sb = StringBuilder()
        // dropLast(1): complete file ends with "" (the terminator); a file
        // cut mid-write ends with a partial line — dropped either way.
        for (line in lines.dropLast(1)) {
            if (line.isEmpty()) continue
            sb.append(unescape(line))
        }
        return sb.toString().ifEmpty { null }
    }

    /** Remove the journal for one stream. No-op when absent. */
    fun delete(dir: File, streamId: String) {
        runCatching { File(dir, streamId + FILE_SUFFIX).delete() }
    }

    /** Remove every journal for the session (clear-chat / session delete). */
    fun deleteAll(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    data class Orphan(val streamId: String, val text: String)

    /**
     * Read + delete every surviving journal, oldest first (file mtime — the
     * write order). Files below [minChars] are deleted WITHOUT recovery: the
     * only sub-threshold files come from the cancel race (an append landing
     * between read and delete), and materializing those would spam the chat
     * with fragments.
     */
    fun recoverOrphans(dir: File, minChars: Int = StreamDurability.FIRST_WRITE_CHARS): List<Orphan> {
        val files = runCatching {
            dir.listFiles { f -> f.name.endsWith(FILE_SUFFIX) }?.sortedBy { it.lastModified() }
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Orphan>()
        for (f in files) {
            val text = readText(dir, f.name.removeSuffix(FILE_SUFFIX))
            if (text != null && text.length >= minChars) {
                out.add(Orphan(f.name.removeSuffix(FILE_SUFFIX), text))
            }
            runCatching { f.delete() }
        }
        if (out.isEmpty()) runCatching { dir.delete() } // keep the tree tidy
        return out
    }

    // ─── escaping ────────────────────────────────────────────────────────────

    internal fun escape(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    internal fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c); i += 1 } // lone backslash: keep verbatim
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }
}
