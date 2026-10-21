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

        val headB = "HEAD_B\n"
        // CANON: END_A and the HEAD of B inside ONE chunk (s4). A completes
        // from the marker; the overshoot after the END line parks into carry.
        val chunk1 = ("OUT_A\n__MINIS_DONE_mA_EXIT_3__\n" + headB).toByteArray()
        val chunk2 = ("__MINIS_DONE_mB_EXIT_0__\n").toByteArray()
        val stream = java.io.PipedInputStream()
        val sink = java.io.PipedOutputStream(stream)
        val p = FakeProcess(stream)

        val reader = spawnReader(drv, p)
        sink.write(chunk1); sink.flush()
        // A's dispatch happens inside the reader; then B takes the slot.
        awaitResumed(resumedA, 1, "03: A resumed from chunk1")
        drv.protocol.start(cbB)
        sink.write(chunk2); sink.flush()
        sink.close()
        reader.join(5000)
        awaitResumed(resumedB, 1, "03: B resumed once (carry folded into B)")

        assertEquals("03: A resumed exactly once", 1, resumedA.get())
        assertTrue("03: A got only its own bytes",
            outA!!.startsWith("OUT_A\n") && !outA!!.contains("HEAD_B"))
        assertEquals("03: B resumed once (same epoch cascade)", 1, resumedB.get())
        assertEquals("03: carry survived into B, zero loss/dup", "HEAD_B\n", outB)
    }

    private fun awaitResumed(counter: AtomicInteger, target: Int, msg: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (counter.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(msg, target, counter.get())
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
}
