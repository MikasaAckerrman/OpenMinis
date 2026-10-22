package com.openminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentShellProtocolDriverIntegrationTest {

    private class FakeProcess(private val script: InputStream) : Process() {
        val stderrBuf = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = script
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    private fun spawnReader(drv: PersistentShellProtocolDriver, p: Process): Thread {
        val ended = CountDownLatch(1)
        val t = Thread {
            try {
                drv.readLoop(p)
            } finally {
                ended.countDown()
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** Acceptance 01: cancel A; A's marker is never dispatched to a dead
     *  callback; B on the same epoch receives ONLY its own output. */
    @Test
    fun i01CancelLateEndNextCommandGetsOnlyOwnOutput() {
        val drv = PersistentShellProtocolDriver {}
        val cbA = drv.createCallback("mA", null)
        val resumedA = AtomicInteger(0)
        cbA.resume = { _, _ -> resumedA.incrementAndGet() }
        drv.armForResume("mA", cbA)
        drv.protocol.start(cbA)

        val detached = drv.protocol.detachCurrent()
        assertNotNull("01: detach must return the in-flight cb", detached)
        drv.dropResumeIfArmed(detached)
        assertEquals("01: cancel resumes A exactly once (death)", 1, resumedA.get())

        // LATE marker bytes for A arrive while the drain is pending: the
        // engine must fold them via drainFold (s1/s6: they can never reach
        // B). The reader below drains them; once the drain closes the
        // arming gate allows B (writer-gate, v2.3 Invariant-C).
        val late = "OUT_A\n__MINIS_DONE_mA_EXIT_0__\n".toByteArray()
        val pDrain = FakeProcess(ByteArrayInputStream(late))
        spawnReader(drv, pDrain).join(5000)
        assertEquals("01: drain-fold never resumes dead A", 1, resumedA.get())

        val bEpoch = "OUT_B\n__MINIS_DONE_mB_EXIT_0__\n".toByteArray()
        val cbB = drv.createCallback("mB", null)
        val resumedB = AtomicInteger(0)
        var outB: String? = null
        cbB.resume = { o, _ -> outB = o; resumedB.incrementAndGet() }
        drv.armForResume("mB", cbB)
        drv.protocol.start(cbB)
        // Only B's epoch: the late A bytes were already consumed by the
        // drain in the previous reader (a second copy would land in B's
        // buffer and prove the opposite of this acceptance).
        val p = FakeProcess(ByteArrayInputStream(bEpoch))
        spawnReader(drv, p).join(5000)

        assertEquals("01: B resumed exactly once", 1, resumedB.get())
        assertEquals("01: B got ONLY its own output", "OUT_B\n", outB)
        assertEquals("01: late END never double-resumes A", 1, resumedA.get())
        assertNull("01: protocol idle after B completed", drv.protocol.marker)
    }

    /** Acceptance 02: END marker SPLIT across chunk boundaries; exit code
     *  must be parsed once the marker line completes; callback removed from
     *  the registry exactly once; output not duplicated. */
    @Test
    fun i02SplitMarkerAcrossChunksExitsCorrectlyOnce() {
        val drv = PersistentShellProtocolDriver {}
        val cb = drv.createCallback("mS", null)
        val resumed = AtomicInteger(0)
        var out: String? = null
        var exit = -77
        cb.resume = { o, e -> out = o; exit = e; resumed.incrementAndGet() }
        drv.armForResume("mS", cb)
        drv.protocol.start(cb)

        // Two chunks with the marker head in the first, digits+suf+"\n" in
        // the second (D-3b + F3 cover both split points; race them here).
        val c1 = ("OUT_S\n__MINIS_DONE_mS_EX".toByteArray())
        val c2 = ("IT_7__\n".toByteArray())
        val p = FakeProcess(ByteArrayInputStream(c1 + c2))
        spawnReader(drv, p).join(5000)

        assertEquals("02: resumed exactly once", 1, resumed.get())
        assertEquals("02: output complete, not duplicated", "OUT_S\n", out)
        assertEquals("02: exit parsed across chunks", 7, exit)
        assertNull("02: protocol idle", drv.protocol.marker)
        assertEquals("02: no parked fragments", 0, drv.protocol.drainParked()?.length ?: 0)
    }

    /** Acceptance 03: END_A and the HEAD of B's output inside ONE chunk
     *  (dispatch + carry passthrough). A must complete with its own bytes;
     *  the after-marker bytes must survive into B's buffer without loss or
     *  duplication - the s4 wiring-level check. */
    @Test
    fun i03EndAPlusHeadBInOneChunkNoLossNoDuplication() {
        val drv = PersistentShellProtocolDriver {}
        val cbA = drv.createCallback("mA", null)
        val resumedA = AtomicInteger(0)
        var outA: String? = null
        var exitA = Int.MIN_VALUE
        cbA.resume = { o, e -> outA = o; exitA = e; resumedA.incrementAndGet() }
        drv.armForResume("mA", cbA)
        drv.protocol.start(cbA)

        val cbB = drv.createCallback("mB", null)
        val resumedB = AtomicInteger(0)
        var outB: String? = null
        cbB.resume = { o, _ -> outB = o; resumedB.incrementAndGet() }

        val headB = "HEAD_B\n"
        // CANON (s4): A's END and B's head inside ONE chunk. feed() returns
        // the Dispatch; resolving it to the caller is the readLoop's job, so
        // the test performs exactly that step (claim -> resume) - the same
        // two calls the reader makes. A pipe is deliberately NOT used: the OS
        // may split or coalesce flushes arbitrarily, which would test pipe
        // scheduling instead of the protocol (reader wiring is covered by
        // i01/i02/i04/i05).
        drv.armForResume("mB", cbB)
        val dA = drv.protocol.feed("OUT_A\n__MINIS_DONE_mA_EXIT_3__\n" + headB)
        assertNotNull("03: A's END produced a dispatch", dA)
        drv.claimForResume(dA!!.marker)!!.resume!!.invoke(dA.output, dA.exitCode)

        assertEquals("03: A resumed exactly once from the shared chunk", 1, resumedA.get())
        assertEquals("03: A exit code from its own marker", 3, exitA)
        assertTrue("03: A got only its own bytes",
            outA!!.startsWith("OUT_A\n") && !outA!!.contains("HEAD_B"))

        // The carried head is handed to the NEXT owner at start(), then its
        // own END folds the buffer and resumes exactly once.
        drv.protocol.start(cbB)
        val dB = drv.protocol.feed("__MINIS_DONE_mB_EXIT_0__\n")
        assertNotNull("03: B's END produced a dispatch", dB)
        drv.claimForResume(dB!!.marker)!!.resume!!.invoke(dB.output, dB.exitCode)

        assertEquals("03: B resumed exactly once", 1, resumedB.get())
        assertEquals("03: carry survived into B, zero loss/dup", "HEAD_B\n", outB)
    }

    /** Acceptance 04: stop() racing a completion. teardownForStop harvests
     *  every armed record; if the reader already CLAIMED the dispatch and
     *  completed, the record is gone from the registry - the harvest loop
     *  must not resume twice. If stop wins, the waiter gets death (-1). */
    @Test
    fun i04StopVersusDispatchExactlyOneWinner() {
        val drv = PersistentShellProtocolDriver {}
        val cb = drv.createCallback("mR", null)
        val resumed = AtomicInteger(0)
        var exit = 42
        cb.resume = { _, e -> exit = e; resumed.incrementAndGet() }
        drv.armForResume("mR", cb)
        drv.protocol.start(cb)

        // (a) reader wins first: dispatch completes and removes the record.
        val p1 = FakeProcess(ByteArrayInputStream("OUT_R\n__MINIS_DONE_mR_EXIT_5__\n".toByteArray()))
        spawnReader(drv, p1).join(5000)
        assertEquals("04a: reader dispatch resumed once", 1, resumed.get())
        assertEquals("04a: exit code from marker", 5, exit)

        // (b) teardown AFTER dispatch: registry empty, harvest resumes nobody.
        drv.teardownForStop()
        assertEquals("04b: stop after dispatch never double-resumes", 1, resumed.get())

        // (c) teardown BEFORE dispatch (arm a fresh record, then harvest it).
        val cb2 = drv.createCallback("mR2", null)
        val resumed2 = AtomicInteger(0)
        var exit2 = 42
        cb2.resume = { _, e -> exit2 = e; resumed2.incrementAndGet() }
        drv.armForResume("mR2", cb2)
        drv.protocol.start(cb2)
        drv.teardownForStop()
        assertEquals("04c: stop harvests the armed record (death)", 1, resumed2.get())
        assertEquals("04c: death exit code", -1, exit2)
        assertEquals("04c: no parked leftover", 0, drv.protocol.drainParked()?.length ?: 0)
        assertNull("04c: protocol idle after stop", drv.protocol.marker)
    }

    /** Acceptance 05: process dies with a NON-EMPTY carry parked in the
     *  engine. EOF teardown must move the carry to the orphan log (mirrored
     *  to diagnostics, never silently dropped), and every waiter gets the
     *  death exit (-1). */
    @Test
    fun i05DeathWithNonEmptyCarryPreservesBytesForDiagnostics() {
        val diags = mutableListOf<String>()
        val drv = PersistentShellProtocolDriver { diags.add(it) }
        val cb = drv.createCallback("mX", null)
        val resumed = AtomicInteger(0)
        var exit = 0
        cb.resume = { _, e -> exit = e; resumed.incrementAndGet() }
        drv.armForResume("mX", cb)
        drv.protocol.start(cb)

        // Chunk ends mid-marker: tail parked in carry, process then EOF-dies.
        val chunk = "OUT_X\n__MINIS_DONE_mX_EX".toByteArray()
        val p = FakeProcess(ByteArrayInputStream(chunk))
        spawnReader(drv, p).join(5000)

        assertEquals("05: waiter resumed (death)", 1, resumed.get())
        assertEquals("05: death exit code", -1, exit)
        val orphaned = diags.firstOrNull { it.startsWith("orphan") }
        assertNotNull("05: carry surfaced via diagnostics, not dropped: $diags", orphaned)
        assertTrue("05: orphan carries the parked fragment bytes",
            orphaned!!.contains("__MINIS_DONE_mX_EX"))
        assertNull("05: protocol idle after death", drv.protocol.marker)
    }
import java.util.concurrent.atomic.AtomicInteger

    /** Acceptance 06 (D3): the process dies leaving an unterminated half
     *  line parked in pendingLine. EOF teardown emits it to the streaming
     *  lineCallback EXACTLY once (a later stop() must not re-emit, the
     *  buffer was reset) and the waiter still gets its death resume once. */
    @Test
    fun i06DeathEmitsParkedHalfLineExactlyOnce() {
        val diags = mutableListOf<String>()
        val drv = PersistentShellProtocolDriver { diags.add(it) }
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val cb = drv.createCallback("mH", { s -> lines.add(s) })
        val resumed = AtomicInteger(0)
        val exit = AtomicInteger(0)
        cb.resume = { _, e -> exit.set(e); resumed.incrementAndGet() }
        drv.armForResume("mH", cb)
        drv.protocol.start(cb)

        // Script: a complete line, then a half line with NO trailing '\n';
        // the stream then ends (process death) - the half line can never
        // be completed by a future chunk, so EOF teardown owns its emission.
        val script = "OUT_ok\nOUT_lost".toByteArray()
        val p = FakeProcess(ByteArrayInputStream(script))
        val rt = spawnReader(drv, p)
        rt.join(5000)
        assertFalse("06: reader exited in time", rt.isAlive)

        // EOF teardown runs inside the reader thread; flushOnDeath must
        // have emitted the parked half line BEFORE resume(-1) - so the
        // subscriber sees both in order, and the parked tail arrives
        // WITHOUT any trailing newline (the process never wrote one).
        assertEquals("06: half line surfaced exactly once", 2, lines.size)
        assertEquals("06: complete line first", "OUT_ok", lines[0])
        assertEquals("06: parked half line emitted at EOF", "OUT_lost", lines[1])

        // (b) A subsequent stop() must NOT re-emit the half line: the EOF
        // teardown already reset pendingLine; flushOnDeath is a no-op now.
        drv.teardownForStop()
        assertEquals("06: stop() after EOF never re-emits", 2, lines.size)
        assertEquals("06: waiter resumed once with death", 1, resumed.get())
        assertEquals("06: death exit code", -1, exit.get())
        assertNull("06: protocol idle after death", drv.protocol.marker)
    }

    /** Acceptance 07 (D2): a multi-byte UTF-8 character SPLIT by the
     *  reader's 4096-byte boundary must surface as the original character,
     *  not as U+FFFD mojibake. ThrottledInputStream makes the split REAL:
     *  ByteArrayInputStream never returns fewer bytes than requested. */
    @Test
    fun i07Utf8SplitAcrossReaderBoundaryNoMojibake() {
        val diags = mutableListOf<String>()
        val drv = PersistentShellProtocolDriver { diags.add(it) }
        val out = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val resumed = AtomicInteger(0)
        val exit = AtomicInteger(-77)
        val cb = drv.createCallback("mU", null)
        cb.resume = { o, _ -> out.set(o); resumed.incrementAndGet() }
        drv.armForResume("mU", cb)

        // Geometry: bytes [0..4094] = ASCII filler 'a' (4095 bytes),
        // byte [4095] = LEAD of a 2-byte char (0xD0), byte [4096] = its
        // continuation (0x96). The reader buffer holds 4096 bytes per
        // read, so WITHOUT throttling the boundary would fall INSIDE the
        // Ж (bytes 4095+4096). This Throttle caps reads to 1 byte in
        // [4095..4096]: the 4096-byte buffer therefore ends right after
        // the filler chunk, and the lead byte arrives in its own 1-byte
        // read followed by the rest - the real split the D2 decode-carry
        // exists for (lead-only remainder, continuation next chunk).
        val filler = "a".repeat(4095)
        val tail = "ЖИЛ-БЫЛ-КОТ..ok\n__MINIS_DONE_mU_EXIT_3__\n"
        val body = (filler + tail).toByteArray()
        // sanity: the split point really falls INSIDE a 2-byte sequence
        assertEquals(0xD0.toByte(), body[4095])
        assertEquals(0x96.toByte(), body[4096])

        val p = FakeProcess(ThrottledInputStream(ByteArrayInputStream(body), 4096, cutPos = 4095, cutEnd = 4096))
        val rt = spawnReader(drv, p)
        rt.join(5000)
        assertFalse("07: reader exited in time", rt.isAlive)

        val got = out.get()
        assertNotNull("07: waiter received the folded output", got)
        assertEquals("07: waiter resumed once", 1, resumed.get())
        assertEquals("07: exit parsed", 3, exit.get())
        // The mojibake U+FFFD must not appear anywhere: the lead byte held
        // in remainder met its continuation in the next chunk.
        assertFalse("07: no U+FFFD", got.contains('\uFFFD'))
        assertTrue("07: split char restored", got.contains("ЖИЛ-БЫЛ"))
        assertTrue(
            "07: filler intact",
            got.startsWith("a".repeat(4095))
        )
    }

    /** Stress 04b: the single-winner guarantee repeated 50x. The race is
     *  made DETERMINISTIC by a gate-stream: the reader sits inside read()
     *  with a live current command, so "stop won" rounds are exactly
     *  teardown-while-armed (i04c shape), and "reader won" rounds feed the
     *  marker only after the gate opens (i04a shape). Alternating rounds
     *  covers both interleavings; scheduler luck is not involved. */
    @Test
    fun i04bStopVersusDispatchFiftyRoundsSingleWinner() {
        for (round in 0 until 50) {
            val readerWins = round % 2 == 0
            val drv = PersistentShellProtocolDriver {
            }
            val marker = "mR4b$round"
            val cb = drv.createCallback(marker, null)
            val resumed = AtomicInteger(0)
            val exit = AtomicInteger(42)
            cb.resume = { _, e ->
                exit.set(e); resumed.incrementAndGet()
            }
            drv.armForResume(marker, cb)
            if (!readerWins) {
                // Gate CLOSED: stream blocks forever unless the gate opens.
                // The reader must NOT consume a marker in this round, so the
                // arrow script only becomes visible when the gate opens -
                // which never happens while stop-first is asserted.
                val gate = java.util.concurrent.CountDownLatch(1)
                val blocking = object : InputStream() {
                    override fun read(): Int {
                        gate.await()
                        return -1
                    }
                }
                val rt = spawnReader(drv, FakeProcess(blocking))
                drv.protocol.start(cb)
                Thread.sleep(30) // let the reader park inside the blocked read()
                // stop wins deterministically: record still armed, reader parked.
                drv.teardownForStop()
                // release the parked reader AFTER the teardown: the next read
                // returns EOF and the reader runs the EOF-teardown over an
                // already-empty registry - a parked stop round never resurrects.
                gate.countDown()
                rt.join(2000)
                assertFalse("04b[$round]: reader exited after release", rt.isAlive)
                assertEquals("04b[$round]: stop harvests unique death", 1, resumed.get())
                assertEquals("04b[$round]: death code", -1, exit.get())
                assertEquals("04b[$round]: registry drained", 0, drv.claimAllResumes().size)
                assertNull("04b[$round]: protocol idle", drv.protocol.marker)
                assertEquals("04b[$round]: nothing parked", 0, drv.protocol.drainParked()?.length ?: 0)
            } else {
                // reader wins first (deterministic): feed, join, assert; then
                // verify the stop-pass cannot resurrect or double-resume.
                val script = "OUT_4b\n__MINIS_DONE_${marker}_EXIT_${round % 6}__\n".toByteArray()
                val rt = spawnReader(drv, FakeProcess(ByteArrayInputStream(script)))
                drv.protocol.start(cb)
                rt.join(5000)
                assertFalse("04b[$round]: reader completed in time", rt.isAlive)
                assertEquals("04b[$round]: dispatch resumed once", 1, resumed.get())
                assertEquals("04b[$round]: real exit", round % 6, exit.get())
                drv.teardownForStop()
                assertEquals("04b[$round]: stop after dispatch is a no-op", 1, resumed.get())
                assertEquals("04b[$round]: registry drained", 0, drv.claimAllResumes().size)
                assertNull("04b[$round]: protocol idle", drv.protocol.marker)
            }
        }
    }

    /** Reader-side stream wrapper: real 4096-boundary splitter.
     *  Every read() is capped at [limit] bytes; in [cutPos..cutEnd] every
     *  read() returns at most ONE byte, forcing the driver's 4096-byte
     *  chunk boundary to land between cutPos and cutPos+1 (inside a
     *  multi-byte UTF-8 char when the geometry is chosen right).
     *  ByteArrayInputStream alone never splits (it fills the whole
     *  buffer), so without this wrapper the decode-carry is exercised
     *  only by the decoder, not through the real stream path. */
    private class ThrottledInputStream(
        private val src: InputStream,
        private val limit: Int,
        private val cutPos: Int,
        private val cutEnd: Int,
    ) : InputStream() {
        private var consumed = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val remainingToCut = cutPos - consumed
            val cap = when {
                consumed in cutPos until cutEnd -> 1
                remainingToCut > 0 -> minOf(limit, len, remainingToCut)
                else -> minOf(limit, len)
            }
            if (cap <= 0) return -1
            val n = src.read(b, off, cap)
            if (n > 0) consumed += n
            return n
        }
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }
    }
}
