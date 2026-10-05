package com.openminis.app.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [crash-2026-10-05_20-30] Regression: the vivo SM8650 platform SQLite is
 * compiled WITHOUT the fts5 module — the schema DDL threw inside
 * MinisApp.onCreate and the app crash-looped. bindEngine must
 * capability-probe, never throw, and leave every public path safe.
 */
class FtsCapabilityFallbackTest {

    private object Fts5MissingEngine : FtsMemoryIndex.SqliteEngine {
        override fun exec(sql: String, vararg binds: Any?) {
            throw RuntimeException("no such module: fts5 (code 1 SQLITE_ERROR)")
        }

        override fun <T> query(
            sql: String,
            binds: Array<out Any?>,
            map: (Array<Any?>) -> T,
        ): List<T> = throw RuntimeException("no such module: fts5")

        override fun close() {
            // nothing to close on a failing engine
        }
    }

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

    @After
    fun teardown() {
        FtsMemoryIndex.resetEngineForTest()
    }

    @Test
    fun `bindEngine survives a missing fts5 module and stays unbound`() {
        val bound = FtsMemoryIndex.bindEngine(Fts5MissingEngine)
        assertFalse("capability probe must report failure", bound)
        // Every consumer path must remain safe with no engine bound:
        assertTrue(FtsMemoryIndex.search("anything").isEmpty())
        assertFalse(FtsMemoryIndex.addExchange("some distilled turn"))
        assertEquals(0L, FtsMemoryIndex.docCount())
        assertEquals(0, FtsMemoryIndex.indexMemoryFiles(java.io.File("/nonexistent")))
    }

    @Test
    fun `bindEngine returns true for a working engine`() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            val bound = FtsMemoryIndex.bindEngine(JdbcEngine(conn))
            assertTrue(bound)
            FtsMemoryIndex.addExchange("USER MESSAGE:\nmemory search works")
            assertTrue(FtsMemoryIndex.search("memory works").isNotEmpty())
        } finally {
            conn.close()
        }
    }
}
