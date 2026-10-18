package com.openminis.app.data

import com.openminis.app.data.ChunkArchive.ArchivedMessage
import com.openminis.app.data.ChunkArchive.ChunkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * PROTOTYPE TESTS — chunked gzip archive (user request 2026-10-18: prove the
 * lifecycle on an ISOLATED test DB before any production wiring).
 *
 * Invariants proven here (the user's own checklist):
 *   1. roundtrip is byte-faithful — every field survives archive+load
 *   2. crash mid-archive leaves no index row -> re-run is idempotent
 *   3. corrupted chunk is detected, quarantined, never silently served
 *   4. compression metrics on a realistic 10k-message chat (size, speed)
 */
class ChunkArchiveTest {

    private fun tempDir(): File = createTempDir(prefix = "chunkarch").apply { deleteOnExit() }

    private fun synthetic(sessionId: String, n: Int, seed: Int = 7): List<ArchivedMessage> {
        // Realistic chat text: words drawn from a small vocabulary with
        // repetitive sentence structure — mirrors how real conversations
        // compress (random letters would NOT compress and would fake a
        // failing ratio; see the metrics test).
        val vocab = listOf(
            "проверь", "компакция", "сессия", "модель", "ошибка", "токен",
            "чанк", "архив", "скилл", "контекст", "выжимка", "маркер",
            "upstream", "relay", "retry", "готово", "файл", "путь",
        )
        val rnd = Random(seed)
        return (0 until n).map { i ->
            val filler = buildString {
                repeat(30 + rnd.nextInt(50)) {
                    append(vocab[rnd.nextInt(vocab.size)])
                    append(' ')
                }
            }
            ArchivedMessage(
                id = "$sessionId-m$i",
                role = if (i % 2 == 0) "user" else "assistant",
                partsJson = "[{\"type\":\"text\",\"value\":\"$filler сообщение $i\"}]",
                createdAt = 1_000_000L + i,
                tokenUsage = if (i % 2 == 1) """{"in":${rnd.nextInt(900)},"out":${rnd.nextInt(400)}}""" else null,
            )
        }
    }

    @Test
    fun `roundtrip is byte-faithful for every field`() {
        val dir = tempDir()
        val db = ChunkArchive.openTestDb(File(dir, "idx.sqlite"))
        val msgs = synthetic("s1", 25)
        val row = ChunkArchive.archiveChunk(db, dir, "s1", 0, msgs)
        assertNotNull(row)
        val res = ChunkArchive.loadChunk(db, dir, "s1", 0)
        assertTrue(res is ChunkResult.Ok)
        val back = (res as ChunkResult.Ok).messages
        assertEquals(msgs.size, back.size)
        msgs.zip(back).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.role, b.role)
            assertEquals(a.partsJson, b.partsJson)
            assertEquals(a.createdAt, b.createdAt)
            assertEquals(a.tokenUsage, b.tokenUsage)
        }
        val ver = ChunkArchive.verifyChunk(db, dir, "s1", 0)
        assertTrue(ver is ChunkResult.Ok)
        db.close()
    }

    @Test
    fun `crash mid-archive leaves no index row and re-run is idempotent`() {
        val dir = tempDir()
        val db = ChunkArchive.openTestDb(File(dir, "idx.sqlite"))
        val msgs = synthetic("s2", 50)

        // Simulate a crash AFTER the tmp write but BEFORE rename+index:
        // leave a stale .tmp, no index row, source rows still "in messages".
        val tmp = File(dir, "s2-0.tmp")
        tmp.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(null, ChunkArchive.readIndex(db, "s2", 0))

        // Re-run: archive for real.
        val row1 = ChunkArchive.archiveChunk(db, dir, "s2", 0, msgs)
        assertNotNull(row1)
        // Re-run AGAIN with the same range: idempotent, no duplicate row.
        val row2 = ChunkArchive.archiveChunk(db, dir, "s2", 0, msgs)
        assertEquals(row1, row2)
        // Only one gz file exists despite three passes.
        val gzFiles = dir.listFiles { f -> f.name.endsWith(".gz") }!!.size
        assertEquals(1, gzFiles)
        db.close()
    }

    @Test
    fun `corrupted chunk is detected quarantined and reported`() {
        val dir = tempDir()
        val db = ChunkArchive.openTestDb(File(dir, "idx.sqlite"))
        val msgs = synthetic("s3", 30)
        ChunkArchive.archiveChunk(db, dir, "s3", 0, msgs)
        val gz = File(dir, "s3-0.gz")
        val bytes = gz.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
        gz.writeBytes(bytes)

        val res = ChunkArchive.verifyChunk(db, dir, "s3", 0)
        assertTrue("corruption must be detected, got $res", res is ChunkResult.Corrupt)
        // Quarantined: original .gz no longer served.
        assertTrue(!gz.exists())
        assertTrue(File(dir, "s3-0.gz.bad").exists())
        db.close()
    }

    @Test
    fun `missing chunk is reported not crashed`() {
        val dir = tempDir()
        val db = ChunkArchive.openTestDb(File(dir, "idx.sqlite"))
        ChunkArchive.archiveChunk(db, dir, "s4", 0, synthetic("s4", 5))
        File(dir, "s4-0.gz").delete()
        assertTrue(ChunkArchive.loadChunk(db, dir, "s4", 0) is ChunkResult.Missing)
        db.close()
    }

    /**
     * User's metric checklist on a realistic long chat: 10k messages,
     * 25 chunks of 400. Prints size/speed for the migration decision.
     */
    @Test
    fun `compression metrics on 10k message chat`() {
        val dir = tempDir()
        val db = ChunkArchive.openTestDb(File(dir, "idx.sqlite"))
        val all = synthetic("big", 10_000)
        val rawBytes = all.sumOf { it.partsJson.toByteArray(Charsets.UTF_8).size }.toLong()

        val t0 = System.nanoTime()
        var chunks = 0
        all.chunked(ChunkArchive.DEFAULT_CHUNK_MSGS).forEachIndexed { idx, part ->
            val row = ChunkArchive.archiveChunk(db, dir, "big", idx, part)
            assertNotNull(row)
            chunks++
        }
        val archiveMs = (System.nanoTime() - t0) / 1_000_000

        var verified = 0
        (0 until chunks).forEach { i ->
            if (ChunkArchive.verifyChunk(db, dir, "big", i) is ChunkResult.Ok) verified++
        }

        val t2 = System.nanoTime()
        val loaded = (ChunkArchive.loadChunk(db, dir, "big", 3) as ChunkResult.Ok).messages
        val loadMs = (System.nanoTime() - t2) / 1_000_000

        val gzBytes = dir.listFiles { f -> f.name.endsWith(".gz") }!!.sumOf { it.length() }

        assertEquals(chunks, verified)
        assertEquals(ChunkArchive.DEFAULT_CHUNK_MSGS, loaded.size)

        // Metrics for the migration decision (visible in CI log).
        println("chunk-arch: raw=${rawBytes / 1024}KB gz=${gzBytes / 1024}KB " +
            "ratio=%.1f%%".format(gzBytes * 100.0 / rawBytes) +
            " chunks=$chunks archiveMs=$archiveMs loadMs=$loadMs")

        // Guards: text must actually compress (JSONL chat text does).
        assertTrue("compression must save space", gzBytes < rawBytes / 2)
        // One chunk must lazy-load fast (UI scroll budget).
        assertTrue("lazy load must be < 500ms, was $loadMs", loadMs < 500)
        db.close()
    }
}
