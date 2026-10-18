package com.openminis.app.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * PROTOTYPE — chunked gzip archive for long-chat history (user request,
 * 2026-10-18). NOT wired into ChatDatabase yet; this is the isolated
 * prototype on a test SQLite the user explicitly asked for BEFORE any
 * production integration:
 *
 *   1. measure real chunk sizes / archive speed / lazy-load latency
 *   2. prove the crash-mid-archive lifecycle (no data loss, no dupes)
 *   3. prove corruption detection (CRC + gunzip failure)
 *
 * Design (user-reviewed): messages below the compact marker are vacuumed
 * out of `messages` into numbered gzip chunks on disk; a thin index table
 * maps (sessionId, chunkIndex) -> id-range + byte size + CRC32 + status.
 * The live table keeps ONLY rows the UI can still reach quickly; archived
 * ranges are rehydrated on scroll via [loadChunk].
 *
 * Chunk lifecycle (atomicity rules the user demanded):
 *   WRITING  — payload rendered to a `.tmp` file, gzip in flight. A crash
 *              here leaves garbage tmp files and NO index row: the source
 *              rows are still in `messages`, so a re-run just rebuilds.
 *   READY    — `.tmp` atomically renamed to `<sessionId>-<idx>.gz`, index
 *              row inserted. Source rows MAY now be deleted.
 *   VERIFIED — chunk re-read from disk, gunzip + CRC32 + message count
 *              re-checked against the index row. Only after this status
 *              does [deleteArchivedSource] run; verification failure
 *              quarantines the file (renamed `.bad`) and the range is
 *              re-archived from the still-present source rows.
 *
 * Idempotency: chunk identity is (sessionId, chunkIndex). A READY/VERIFIED
 * chunk covering the exact same id range is a skip, never a duplicate.
 */
object ChunkArchive {

    /** Pure data the archive needs per message — no Room/Android types. */
    data class ArchivedMessage(
        val id: String,
        val role: String,
        val partsJson: String,
        val createdAt: Long,
        val tokenUsage: String?,
    )

    enum class Status { WRITING, READY, VERIFIED }

    data class ChunkIndexRow(
        val sessionId: String,
        val chunkIndex: Int,
        val firstId: String,
        val lastId: String,
        val msgCount: Int,
        val byteSize: Long,
        val crc32: Long,
        val status: Status,
    )

    sealed class ChunkResult {
        data class Ok(val messages: List<ArchivedMessage>) : ChunkResult()
        data class Corrupt(val reason: String) : ChunkResult()
        data class Missing(val reason: String) : ChunkResult()
    }

    const val DEFAULT_CHUNK_MSGS = 400
    const val VERIFY_READ_LIMIT_BYTES = 64L * 1024 * 1024

    /** One JSONL line per message; parse back with [parseLine]. */
    fun renderLine(m: ArchivedMessage): String {
        val esc = { s: String ->
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        }
        val usage = m.tokenUsage?.let { "\"${esc(it)}\"" } ?: "null"
        return "{\"id\":\"${esc(m.id)}\",\"role\":\"${esc(m.role)}\"," +
            "\"parts\":\"${esc(m.partsJson)}\",\"ts\":${m.createdAt},\"usage\":$usage}"
    }

    fun parseLine(line: String): ArchivedMessage? {
        val unesc = { s: String ->
            s.replace("\\r", "\r").replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        // Minimal structured parser for the fixed shape renderLine emits.
        val id = extract(line, "\"id\":\"") ?: return null
        val role = extract(line, "\"role\":\"") ?: return null
        val parts = extract(line, "\"parts\":\"") ?: return null
        val tsIdx = line.indexOf("\"ts\":")
        if (tsIdx < 0) return null
        val tsRest = line.substring(tsIdx + 5)
        val tsEnd = tsRest.indexOf(',')
        val ts = tsRest.substring(0, if (tsEnd < 0) tsRest.length else tsEnd).toLongOrNull() ?: return null
        val usage = extract(line, "\"usage\":\"")
        return ArchivedMessage(
            id = unesc(id),
            role = unesc(role),
            partsJson = unesc(parts),
            createdAt = ts,
            tokenUsage = usage?.let { unesc(it) },
        )
    }

    private fun extract(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val from = i + key.length
        val out = StringBuilder()
        var j = from
        while (j < line.length) {
            val c = line[j]
            if (c == '\\' && j + 1 < line.length) {
                out.append(line[j + 1])
                j += 2
                continue
            }
            if (c == '"') return out.toString()
            out.append(c)
            j++
        }
        return null
    }

    /**
     * Archives [messages] (MUST be contiguous ascending by sort) as chunk
     * [chunkIndex] of [sessionId]. Returns the index row on success.
     * Idempotent: an existing READY/VERIFIED row with the same id range is
     * returned untouched (no duplicate file, no rewrite).
     */
    fun archiveChunk(
        db: Connection,
        dir: File,
        sessionId: String,
        chunkIndex: Int,
        messages: List<ArchivedMessage>,
    ): ChunkIndexRow? {
        if (messages.isEmpty()) return null
        val existing = readIndex(db, sessionId, chunkIndex)
        if (existing != null &&
            existing.firstId == messages.first().id &&
            existing.lastId == messages.last().id &&
            existing.status != Status.WRITING
        ) {
            return existing // idempotent re-run: already archived
        }

        val body = messages.joinToString("\n") { renderLine(it) }
        val bytes = body.toByteArray(Charsets.UTF_8)

        // WRITING: tmp file first, atomic rename after a full gzip pass.
        val tmp = File(dir, "${sessionId}-${chunkIndex}.tmp")
        val gz = File(dir, "${sessionId}-${chunkIndex}.gz")
        val crc = CRC32()
        GZIPOutputStream(tmp.outputStream().buffered(64 * 1024)).use { gzOut ->
            gzOut.write(bytes)
        }
        crc.update(bytes)
        if (!tmp.renameTo(gz)) {
            gz.delete()
            if (!tmp.renameTo(gz)) return null // fs trouble; caller retries
        }

        val row = ChunkIndexRow(
            sessionId = sessionId,
            chunkIndex = chunkIndex,
            firstId = messages.first().id,
            lastId = messages.last().id,
            msgCount = messages.size,
            byteSize = gz.length(),
            crc32 = crc.value.toLong() and 0xffffffffL,
            status = Status.READY,
        )
        upsertIndex(db, row)
        return row
    }

    /**
     * VERIFIED transition: re-read the chunk from disk, gunzip, check CRC
     * and message count. Quarantines (`.bad` rename) on any mismatch so a
     * re-archive from live rows can rebuild it. Source rows must STILL be
     * present in `messages` when this runs — that ordering is what makes
     * the whole pipeline lossless.
     */
    fun verifyChunk(db: Connection, dir: File, sessionId: String, chunkIndex: Int): ChunkResult {
        val row = readIndex(db, sessionId, chunkIndex)
            ?: return ChunkResult.Missing("no index row for $sessionId#$chunkIndex")
        val gz = File(dir, "${sessionId}-${chunkIndex}.gz")
        if (!gz.exists()) return ChunkResult.Missing("file missing: ${gz.name}")
        return try {
            val raw = GZIPInputStream(gz.inputStream().buffered(64 * 1024)).use { it.readBytes() }
            if (raw.size > VERIFY_READ_LIMIT_BYTES) return ChunkResult.Corrupt("chunk over read limit")
            val crc = CRC32()
            crc.update(raw)
            if (crc.value.toLong() and 0xffffffffL != row.crc32) {
                quarantine(gz)
                ChunkResult.Corrupt("crc mismatch")
            } else {
                val lines = raw.toString(Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
                if (lines.size != row.msgCount) {
                    quarantine(gz)
                    ChunkResult.Corrupt("count mismatch: ${lines.size} != ${row.msgCount}")
                } else {
                    upsertIndex(db, row.copy(status = Status.VERIFIED))
                    ChunkResult.Ok(lines.mapNotNull { parseLine(it) })
                }
            }
        } catch (e: Exception) {
            quarantine(gz)
            ChunkResult.Corrupt("gunzip failed: ${e.message}")
        }
    }

    /** Lazy-load path for the UI: verify-lite (gunzip) + parse. */
    fun loadChunk(db: Connection, dir: File, sessionId: String, chunkIndex: Int): ChunkResult {
        val row = readIndex(db, sessionId, chunkIndex)
            ?: return ChunkResult.Missing("no index row")
        val gz = File(dir, "${sessionId}-${chunkIndex}.gz")
        if (!gz.exists()) return ChunkResult.Missing("file missing: ${gz.name}")
        return try {
            val raw = GZIPInputStream(gz.inputStream().buffered(64 * 1024)).use { it.readBytes() }
            val crc = CRC32()
            crc.update(raw)
            if (crc.value.toLong() and 0xffffffffL != row.crc32) {
                ChunkResult.Corrupt("crc mismatch — chunk quarantined on next verify")
            } else {
                ChunkResult.Ok(
                    raw.toString(Charsets.UTF_8).split('\n')
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseLine(it) },
                )
            }
        } catch (e: Exception) {
            ChunkResult.Corrupt("gunzip failed: ${e.message}")
        }
    }

    private fun quarantine(gz: File) {
        val bad = File(gz.parentFile, gz.name + ".bad")
        gz.renameTo(bad) || gz.delete()
    }

    // ---- index table (prototype: standalone test DB) ----

    fun createIndexTable(db: Connection) {
        db.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS archive_chunks (" +
                    "session_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, " +
                    "first_id TEXT NOT NULL, last_id TEXT NOT NULL, " +
                    "msg_count INTEGER NOT NULL, byte_size INTEGER NOT NULL, " +
                    "crc32 INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "PRIMARY KEY(session_id, chunk_index))",
            )
        }
    }

    fun readIndex(db: Connection, sessionId: String, chunkIndex: Int): ChunkIndexRow? =
        db.prepareStatement(
            "SELECT first_id,last_id,msg_count,byte_size,crc32,status FROM archive_chunks " +
                "WHERE session_id=? AND chunk_index=?",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setInt(2, chunkIndex)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else ChunkIndexRow(
                    sessionId, chunkIndex,
                    rs.getString(1), rs.getString(2),
                    rs.getInt(3), rs.getLong(4), rs.getLong(5),
                    Status.valueOf(rs.getString(6)),
                )
            }
        }

    private fun upsertIndex(db: Connection, row: ChunkIndexRow) {
        db.prepareStatement(
            "INSERT INTO archive_chunks(session_id,chunk_index,first_id,last_id,msg_count," +
                "byte_size,crc32,status) VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(session_id,chunk_index) DO UPDATE SET " +
                "first_id=excluded.first_id,last_id=excluded.last_id,msg_count=excluded.msg_count," +
                "byte_size=excluded.byte_size,crc32=excluded.crc32,status=excluded.status",
        ).use { ps ->
            ps.setString(1, row.sessionId)
            ps.setInt(2, row.chunkIndex)
            ps.setString(3, row.firstId)
            ps.setString(4, row.lastId)
            ps.setInt(5, row.msgCount)
            ps.setLong(6, row.byteSize)
            ps.setLong(7, row.crc32)
            ps.setString(8, row.status.name)
            ps.executeUpdate()
        }
    }

    /** Convenience for tests/prototyping: open the standalone index DB. */
    fun openTestDb(file: File): Connection {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        createIndexTable(c)
        return c
    }
}
