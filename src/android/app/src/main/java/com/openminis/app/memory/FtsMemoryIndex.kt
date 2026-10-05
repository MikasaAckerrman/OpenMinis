package com.openminis.app.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * [T-m13-fts-memory] The SQLite-FTS5 associative store — the M13 successor
 * to the supermemory Node server for the memory-search surface.
 *
 * Why this exists (measured on-device, 01–05.10):
 *  - the Node server costs a 10–26s boot, ~245MB RSS and periodic 3.5s
 *    timeouts exactly when the CPU is hot — for a SEARCH that has to stay
 *    under a 600ms prompt budget;
 *  - FTS5 lives in-process on the platform's native SQLite (C, not a
 *    managed daemon): zero boot, zero extra RAM, millisecond queries.
 *
 * Corpus (both sources land in the same table):
 *  1. distilled exchanges — the distiller pushes "USER MESSAGE…ASSISTANT
 *     TURN" blocks (the same content it used to POST to the server);
 *  2. the keyword corpus — daily logs + GLOBAL.md, indexed incrementally
 *     by (size, mtime) stat checks: changed files re-index, untouched
 *     files cost one stat call.
 *
 * Search: prefix-token OR-match (recall-oriented), bm25 ranking,
 * [sqlite snippet] excerpts. Honest quality note: FTS is lexical, not
 * semantic — it does not "understand" paraphrases the way the embedding
 * server claimed to; what it guarantees is that every literal keyword in
 * a query finds its occurrences in milliseconds, which covers the
 * observed recall cases (paths, error strings, names, dates).
 *
 * Testability: all SQL flows through the [SqliteEngine] seam — the JVM
 * suite runs the identical SQL against sqlite-jdbc (see
 * FtsMemoryIndexTest); the Android adapter is a 30-line wrapper.
 */
object FtsMemoryIndex {

    /** Minimal SQL surface the core needs; keeps the core JVM-testable. */
    internal interface SqliteEngine {
        fun exec(sql: String, vararg binds: Any?)
        fun <T> query(sql: String, binds: Array<out Any?>, map: (Array<Any?>) -> T): List<T>
        fun close()
    }

    internal class AndroidEngine(db: SQLiteDatabase) : SqliteEngine {
        internal val db = db
        override fun exec(sql: String, vararg binds: Any?) {
            db.execSQL(sql, binds)
        }

        override fun <T> query(
            sql: String,
            binds: Array<out Any?>,
            map: (Array<Any?>) -> T,
        ): List<T> {
            val out = mutableListOf<T>()
            db.rawQuery(sql, binds.map { if (it == null) null else it.toString() }.toTypedArray())
                .use { c ->
                    while (c.moveToNext()) {
                        val row = Array<Any?>(c.columnCount) { i ->
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                else -> null
                            }
                        }
                        out += map(row)
                    }
                }
            return out
        }

        override fun close() {
            db.close()
        }
    }

    /**
     * [T-m13-fts-bundle] Engine on the bundled requery SQLite (FTS5
     * guaranteed). Same [SqliteEngine] contract, so every query/mapping
     * above is engine-agnostic by construction.
     */
    internal class RequeryEngine(
        private val db: io.requery.android.database.sqlite.SQLiteDatabase,
    ) : SqliteEngine {
        override fun exec(sql: String, vararg binds: Any?) {
            if (binds.isEmpty()) {
                db.execSQL(sql)
            } else {
                @Suppress("UNCHECKED_CAST") db.execSQL(sql, binds as Array<Any?>)
            }
        }

        override fun <T> query(
            sql: String,
            binds: Array<out Any?>,
            map: (Array<Any?>) -> T,
        ): List<T> {
            val out = mutableListOf<T>()
            @Suppress("UNCHECKED_CAST") db.query(sql, binds as Array<Any?>).use { c ->
                while (c.moveToNext()) {
                    val row = Array<Any?>(c.columnCount) { i ->
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> null
                        }
                    }
                    out += map(row)
                }
            }
            return out
        }

        override fun close() {
            db.close()
        }
    }

    @Volatile
    private var engine: SqliteEngine? = null

    /** [T-m13-diag] Last indexing failure (per-file catch is silent in prod by design). */
    @Volatile
    var lastIndexError: String? = null
        private set

    private val initLock = Any()

    /** Bind the backing DB; idempotent, safe from any thread. */
    fun init(context: Context) {
        if (engine != null) return
        synchronized(initLock) {
            if (engine != null) return
            val dir = File(File(context.filesDir, "minis-global"), "memory-fts")
            if (dir.exists() || dir.mkdirs()) {
                val file = File(dir, "memory-fts.db")
                // Capability cascade [T-m13-fts-bundle]: (1) platform
                // SQLite (has FTS5 on most builds), (2) the bundled
                // requery SQLite (FTS5 guaranteed, vivo SM8650's platform
                // engine lacks the module), (3) the supermemory server.
                val platform = try {
                    SQLiteDatabase.openOrCreateDatabase(file, null)
                } catch (t: Throwable) {
                    null
                }
                if (platform != null && bindEngine(AndroidEngine(platform))) {
                    return
                }
                try {
                    platform?.close()
                } catch (ignore: Throwable) {
                }
                val bundled = try {
                    io.requery.android.database.sqlite.SQLiteDatabase
                        .openOrCreateDatabase(file, null)
                } catch (t: Throwable) {
                    null
                }
                if (bundled != null && bindEngine(RequeryEngine(bundled))) {
                    return
                }
                try {
                    bundled?.close()
                } catch (ignore: Throwable) {
                }
                // Both engines failed the capability probe: the memory
                // path stays on the supermemory server (never crashes).
                revertRouterToServer()
            }
        }
    }

    /**
     * [crash-2026-10-05_20-30] Capability probe + bind. The PLATFORM
     * SQLite may be compiled without the fts5 module (vivo SM8650
     * Android 15: "no such module: fts5"). A memory backend must NEVER
     * take the app down: probe + bind, never throw. The ROUTER decision
     * (flip to the server) is made by [init] only when the whole
     * cascade fails — an intermediate engine's failure must not flip
     * while a later engine can still bind. Returns true when bound.
     */
    internal fun bindEngine(e: SqliteEngine): Boolean {
        synchronized(initLock) {
            if (engine != null) return true
            return try {
                ensureSchema(e)
                engine = e
                true
            } catch (t: Throwable) {
                try {
                    e.close()
                } catch (closeFailure: Throwable) {
                    // close best-effort; the original failure wins
                }
                // JVM-safe logging (android.util.Log would break the unit
                // suite; stdout is visible in logcat on device).
                println(
                    "[FtsMemoryIndex] engine rejected (${t.message}) — " +
                        "trying the next backend in the cascade",
                )
                false
            }
        }
    }

    /** True when every engine in the cascade failed (router → server). */
    private fun revertRouterToServer() {
        if (MemorySearchPrefs.isInitialized() && MemorySearchPrefs.useFts()) {
            MemorySearchPrefs.setUseFts(false)
        }
        println(
            "[FtsMemoryIndex] FTS5 unavailable on this build — memory " +
                "search reverts to the supermemory server",
        )
    }

    /** Test entry: run against a caller-provided engine (JDBC etc.). */
    internal fun initWith(engineForTest: SqliteEngine, reset: Boolean = true) {
        synchronized(initLock) {
            // Schema FIRST: a fresh in-memory DB has no tables yet — the
            // reset deletes below would throw before any test ran (the
            // zombie-CI lesson: a green run proves nothing unless the
            // tests actually executed).
            ensureSchema(engineForTest)
            if (reset) engineForTest.exec("DELETE FROM docs")
            engineForTest.exec("DELETE FROM ingested_files")
            engine = engineForTest
        }
    }

    internal fun resetForTest() {
        synchronized(initLock) {
            engine?.exec("DELETE FROM docs")
            engine?.exec("DELETE FROM ingested_files")
        }
    }

    /** Detach the engine (JVM suite teardown — never on device). */
    internal fun resetEngineForTest() {
        synchronized(initLock) {
            engine = null
            lastFileScanMs = 0L
        }
    }

    private fun ensureSchema(e: SqliteEngine) {
        e.exec(
            "CREATE VIRTUAL TABLE IF NOT EXISTS docs USING fts5(" +
                "content, tokenize='unicode61')",
        )
        e.exec(
            "CREATE TABLE IF NOT EXISTS ingested_files(" +
                "path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, " +
                "doc_rowid INTEGER NOT NULL)",
        )
    }

    private fun requireEngine(): SqliteEngine? = engine

    /**
     * Write path (the distiller's exchange blocks). Append-only — one row
     * per distilled turn, same content the Node server received.
     */
    fun addExchange(content: String): Boolean {
        val e = requireEngine() ?: return false
        if (content.isBlank()) return false
        return try {
            e.exec("INSERT INTO docs(content) VALUES (?)", content)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Volatile
    private var lastFileScanMs = 0L

    /**
     * [T-m13-fts-memory] Prompt-path entry: stat-refresh at most once per
     * 30s (a scan is a few dozen stat calls; the throttle keeps the prompt
     * build at strict milliseconds). Cheap when nothing changed.
     */
    fun ensureFilesIndexed(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFileScanMs < 30_000L) return
        lastFileScanMs = now
        val dir = java.io.File(
            java.io.File(context.filesDir, "minis-global"),
            "memory",
        )
        try {
            indexMemoryFiles(dir)
        } catch (t: Throwable) {
            // never let the memory tier break a send
            lastIndexError = "scan: ${t.message}"
        }
    }

    /** [T-m13-diag] Engine bound + which tier of the cascade won. */
    fun engineBound(): String? {
        val e = engine ?: return null
        return when (e) {
            is AndroidEngine -> "platform"
            is RequeryEngine -> "bundled"
            else -> e.javaClass.simpleName
        }
    }

    /** [T-m13-diag] Live self-test for the RPC surface. */
    fun selfTest(): Map<String, Any?> = mapOf(
        "engine" to (engineBound() ?: "unbound"),
        "docs" to docCount(),
        "lastIndexError" to lastIndexError,
    )

    /**
     * Incremental keyword-corpus indexing: stat-check every file, re-index
     * only changed ones. Returns the number of re-indexed files.
     */
    fun indexMemoryFiles(dir: File): Int {
        val e = requireEngine() ?: return 0
        if (!dir.isDirectory) return 0
        var reindexed = 0
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?: return 0
        for (f in files) {
            val known = e.query(
                "SELECT size, mtime, doc_rowid FROM ingested_files WHERE path = ?",
                arrayOf(f.absolutePath),
            ) { row -> Triple(row[0], row[1], row[2]) }
                .firstOrNull()
            val size = f.length()
            val mtime = f.lastModified()
            if (known != null &&
                (known.first as? Number)?.toLong() == size &&
                (known.second as? Number)?.toLong() == mtime
            ) {
                continue
            }
            try {
                val text = f.readText()
                if (known != null) {
                    e.exec("DELETE FROM docs WHERE rowid = ?", (known.third as? Number)?.toLong())
                }
                e.exec(
                    "INSERT INTO docs(content) VALUES (?)",
                    "FILE ${f.name}\n$text",
                )
                val rowid = e.query<Long>(
                    "SELECT last_insert_rowid()",
                    emptyArray(),
                ) { row -> (row[0] as? Number)?.toLong() ?: 0L }.firstOrNull() ?: 0L
                e.exec(
                    "INSERT OR REPLACE INTO ingested_files(path, size, mtime, doc_rowid) " +
                        "VALUES (?, ?, ?, ?)",
                    f.absolutePath, size, mtime, rowid,
                )
                reindexed++
            } catch (t: Throwable) {
                // unreadable file: skip, stat stays unchanged, no loop
                lastIndexError = "${f.name}: ${t.message}"
            }
        }
        return reindexed
    }

    /**
     * Search: prefix-OR match over the tokenized query, bm25-ranked,
     * snippet excerpts. Lexical, deterministic, millisecond-scale.
     */
    fun search(query: String, limit: Int = 8): List<SupermemoryBridge.Hit> {
        val e = requireEngine() ?: return emptyList()
        val match = buildMatchQuery(query) ?: return emptyList()
        return try {
            e.query(
                "SELECT rowid, snippet(docs, 0, '»', '«', '…', 16), bm25(docs) " +
                    "FROM docs WHERE docs MATCH ? ORDER BY bm25(docs) LIMIT ?",
                arrayOf(match, limit),
            ) { row ->
                val rank = (row[2] as? Double) ?: 0.0
                SupermemoryBridge.Hit(
                    id = "fts-${row[0]}",
                    content = row[1]?.toString() ?: "",
                    // bm25 is a distance (smaller = better); flip to a
                    // similarity-ish score for the shared Hit contract.
                    score = if (rank >= 0.0) 0.0 else -rank,
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Query → FTS5 MATCH string. Recall-oriented: each alphanumeric token
     * becomes a prefix term, OR-joined (a hit matching ANY key term is a
     * candidate; bm25 sorts the ones matching more/rarer tokens up).
     * FTS operators (", ^, -, *, NEAR, AND, OR…) are neutralized by the
     * token whitelist — model-provided input can never become SQL.
     */
    internal fun buildMatchQuery(query: String): String? {
        val tokens = Regex("[\\p{L}\\p{N}_]{2,}")
            .findAll(query)
            .map { it.value }
            .distinct()
            .take(8)
            .toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { t -> "\"$t\"*" }
    }

    /** Total indexed rows (diagnostics / debug RPC). */
    fun docCount(): Long {
        val e = requireEngine() ?: return 0L
        return try {
            e.query("SELECT COUNT(*) FROM docs", emptyArray()) { row ->
                (row[0] as? Long) ?: 0L
            }.firstOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }
}
