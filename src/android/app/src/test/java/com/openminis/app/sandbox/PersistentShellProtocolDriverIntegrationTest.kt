package com.openminis.app.sandbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertNotNull(detached, "01: detach must return the in-flight cb")
        drv.dropResumeIfArmed(detached)
        assertEquals(1, resumedA.get(), "01: cancel resumes A exactly once (death)")

        // LATE marker bytes for A arrive while the drain is pending: the
        // engine must fold them via drainFold (s1/s6: they can never reach
        // B). The reader below drains them; once the drain closes the
        // arming gate allows B (writer-gate, v2.3 Invariant-C).
        val late = "OUT_A\n__MINIS_DONE_mA_EXIT_0__\n".toByteArray()
        val pDrain = FakeProcess(ByteArrayInputStream(late))
        spawnReader(drv, pDrain).join(5000)
        assertEquals(1, resumedA.get(), "01: drain-fold never resumes dead A")

        val bEpoch = "OUT_B\n__MINIS_DONE_mB_EXIT_0__\n".toByteArray()
        val cbB = drv.createCallback("mB", null)
        val resumedB = AtomicInteger(0)
        var outB: String? = null
        cbB.resume = { o, _ -> outB = o; resumedB.incrementAndGet() }
        drv.armForResume("mB", cbB)
        drv.protocol.start(cbB)
        val p = FakeProcess(ByteArrayInputStream(late + bEpoch))
        spawnReader(drv, p).join(5000)

        assertEquals(1, resumedB.get(), "01: B resumed exactly once")
        assertEquals("OUT_B\n", outB, "01: B got ONLY its own output")
        assertEquals(1, resumedA.get(), "01: late END never double-resumes A")
        assertNull(drv.protocol.current, "01: protocol idle after B completed")
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

        assertEquals(1, resumed.get(), "02: resumed exactly once")
        assertEquals("OUT_S\n", out, "02: output complete, not duplicated")
        assertEquals(7, exit, "02: exit parsed across chunks")
        assertNull(drv.protocol.current, "02: protocol idle")
        assertEquals(0, drv.protocol.drainParked()?.length ?: 0, "02: no parked fragments")
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
        cbA.resume = { o, _ -> outA = o; resumedA.incrementAndGet() }
        drv.armForResume("mA", cbA)
        drv.protocol.start(cbA)

        // Pre-arm B's registry record, then push the reader through the SAME
        // feed cascade the real readLoop uses: chunk1 completes A (marker +
        // head of B in ONE chunk -> carry), the writer-side start(B) follows
        // (exactly as executeCommand does under the writeMutex), then chunk2
        // delivers B's END - zero loss, zero duplication (s4 semantics).
        val cbB = drv.createCallback("mB", null)
        val resumedB = AtomicInteger(0)
        var outB: String? = null
        cbB.resume = { o, _ -> outB = o; resumedB.incrementAndGet() }
        drv.armForResume("mB", cbB)

        val headB = "HEAD_B\n"
        val chunk1 = ("OUT_A\n__MINIS_DONE_mA_EXIT_3__\n" + headB).toByteArray()
        val chunk2 = "__MINIS_DONE_mB_EXIT_0__\n".toByteArray()
        val stream = java.io.PipedInputStream()
        val sink = java.io.PipedOutputStream(stream)
        val p = FakeProcess(stream)

        // Reader thread consumes the stream exactly as readLoop does.
        spawnReader(drv, p)
        sink.write(chunk1); sink.flush()
        // Writer-side: A's dispatch happened during chunk1's feed; arm B's
        // engine slot now (RELEASE-slot semantics, discharge A first).
        awaitResumed(resumedA, 1, "03: A resumed from chunk1")
        drv.protocol.start(cbB)
        sink.write(chunk2); sink.flush()
        sink.close()

        assertEquals(1, resumedA.get(), "03: A resumed exactly once")
        assertEquals(3, exitOf(outA), "03: A exit via marker")
        assertTrue(outA!!.startsWith("OUT_A\n") && !outA!!.contains("HEAD_B"),
            "03: A got only its own bytes")
        assertEquals(1, resumedB.get(), "03: B resumed once (same epoch cascade)")
        assertEquals("HEAD_B\n", outB, "03: carry survived into B, zero loss/dup")
    }

    private fun awaitResumed(counter: AtomicInteger, target: Int, msg: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (counter.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(target, counter.get(), msg)
    }

    private fun exitOf(out: String?): Int =
        out?.substringAfterLast("__MINIS_DONE_", "")?.let { raw ->
            val m = Regex("_EXIT_(\\d+)__").find(raw)
            m?.groupValues?.get(1)?.toIntOrNull() ?: -1
        } ?: -1

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
        assertEquals(1, resumed.get(), "04a: reader dispatch resumed once")
        assertEquals(5, exit, "04a: exit code from marker")

        // (b) teardown AFTER dispatch: registry empty, harvest resumes nobody.
        drv.teardownForStop()
        assertEquals(1, resumed.get(), "04b: stop after dispatch never double-resumes")

        // (c) teardown BEFORE dispatch (arm a fresh record, then harvest it).
        val cb2 = drv.createCallback("mR2", null)
        val resumed2 = AtomicInteger(0)
        var exit2 = 42
        cb2.resume = { _, e -> exit2 = e; resumed2.incrementAndGet() }
        drv.armForResume("mR2", cb2)
        drv.protocol.start(cb2)
        drv.teardownForStop()
        assertEquals(1, resumed2.get(), "04c: stop harvests the armed record (death)")
        assertEquals(-1, exit2, "04c: death exit code")
        assertEquals(0, drv.protocol.drainParked()?.length ?: 0, "04c: no parked leftover")
        assertNull(drv.protocol.current, "04c: protocol idle after stop")
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

        assertEquals(1, resumed.get(), "05: waiter resumed (death)")
        assertEquals(-1, exit, "05: death exit code")
        val orphaned = diags.firstOrNull { it.startsWith("orphan") }
        assertNotNull(orphaned, "05: carry surfaced via diagnostics, not dropped: $diags")
        assertTrue(orphaned!!.contains("__MINIS_DONE_mX_EX"),
            "05: orphan carries the parked fragment bytes")
        assertNull(drv.protocol.current, "05: protocol idle after death")
    }
}
