package com.openminis.app.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-m13-fts-memory] The FTS store against a REAL SQLite engine: the JVM
 * suite runs the identical SQL the Android adapter executes (the
 * [FtsMemoryIndex.SqliteEngine] seam) — schema, ingest, stat-incremental
 * file indexing, prefix search, ranking, snippet extraction and the
 * operator-neutralizing query builder.
 */
class FtsMemoryIndexTest {

    private class JdbcEngine(conn: Connection) : FtsMemoryIndex.SqliteEngine {
        private val c = conn
        override fun exec(sql: String, vararg binds: Any?) {
            c.prepareStatement(sql).use { ps ->
                binds.forEachIndexed { i, b -> ps.setObject(i + 1, b) }
                ps.executeUpdate()
            }
        }

        override fun <T> query(
            sql: String,
            binds: Array<out Any?>,
            map: (Array<Any?>) -> T,
        ): List<T> {
            val out = mutableListOf<T>()
            c.prepareStatement(sql).use { ps ->
                binds.forEachIndexed { i, b -> ps.setObject(i + 1, b) }
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    while (rs.next()) {
                        out += map(Array(meta.columnCount) { i -> rs.getObject(i + 1) })
                    }
                }
            }
            return out
        }

        override fun close() {
            c.close()
        }
    }

    private fun withEngine(block: () -> Unit) {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        FtsMemoryIndex.initWith(JdbcEngine(conn), reset = true)
        try {
            block()
        } finally {
            FtsMemoryIndex.resetEngineForTest()
            conn.close()
        }
    }

    @After
    fun tearDown() {
        // each test opens/closes its own engine via withEngine
    }

    @Test
    fun `exchange ingest is searchable by any keyword`() {
        withEngine {
            FtsMemoryIndex.addExchange(
                "USER MESSAGE:\nпоставь термал-страж на фон\n\nASSISTANT TURN:\nnohup thermal_watch.sh запущен",
            )
            val hits = FtsMemoryIndex.search("термал-страж")
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.first().content.contains("термал"))
        }
    }

    @Test
    fun `prefix search matches word forms`() {
        withEngine {
            FtsMemoryIndex.addExchange(
                "USER MESSAGE:\nоптимизировали отправку\n\nASSISTANT TURN:\ndone",
            )
            // "оптимиз*" must match "оптимизировали" (prefix semantics)
            val hits = FtsMemoryIndex.search("оптимиз")
            assertTrue(hits.isNotEmpty())
        }
    }

    @Test
    fun `empty or operator-only queries return nothing without SQL errors`() {
        withEngine {
            assertNull(FtsMemoryIndex.buildMatchQuery(""))
            assertNull(FtsMemoryIndex.buildMatchQuery("!!! ???"))
            // FTS operators neutralized: no exception, tokens only
            assertEquals("\"NEAR\"* OR \"AND\"*", FtsMemoryIndex.buildMatchQuery("NEAR AND"))
            assertTrue(FtsMemoryIndex.search("!!! ???").isEmpty())
        }
    }

    @Test
    fun `bm25 ranks the more relevant doc first`() {
        withEngine {
            FtsMemoryIndex.addExchange("alpha beta beta beta gamma")
            FtsMemoryIndex.addExchange("alpha delta delta delta epsilon")
            FtsMemoryIndex.addExchange("irrelevant unrelated content")
            val hits = FtsMemoryIndex.search("beta gamma")
            assertTrue(hits.isNotEmpty())
            // doc 1 is the only beta+gamma doc — must rank first
            assertEquals("fts-1", hits.first().id)
        }
    }

    @Test
    fun `file indexing is stat-incremental`() {
        withEngine {
            val dir = File.createTempFile("ftsdir", "").let { f ->
                f.delete(); f.mkdirs(); f
            }
            val file1 = File(dir, "2026-10-05.md").apply {
                writeText("## тема дня\nпровайдеры лагали — починили\n")
            }
            val first = FtsMemoryIndex.indexMemoryFiles(dir)
            assertEquals(1, first)
            // unchanged: zero re-index
            assertEquals(0, FtsMemoryIndex.indexMemoryFiles(dir))
            // searchable
            assertTrue(FtsMemoryIndex.search("провайдеры").isNotEmpty())
            // edit: exactly one re-index, new content visible, old gone
            file1.writeText("## тема дня\nновая тема: m13\n")
            assertEquals(1, FtsMemoryIndex.indexMemoryFiles(dir))
            assertTrue(FtsMemoryIndex.search("m13").isNotEmpty())
            assertTrue(FtsMemoryIndex.search("провайдеры").isEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `match builder caps tokens and dedups`() {
        val q = FtsMemoryIndex.buildMatchQuery("alpha alpha beta gamma delta epsilon zeta eta theta iota kappa")
        assertNotNull(q)
        // 10 distinct words -> capped at 8 terms
        assertEquals(8, q!!.split(" OR ").size)
    }
}
