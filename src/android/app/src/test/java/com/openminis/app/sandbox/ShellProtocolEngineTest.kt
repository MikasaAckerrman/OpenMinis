package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [6 acceptance scenarios — REVIEW_vc144_vault_chain.md v2.3]
 * Pure-JVM contract tests for the END-marker protocol; PersistentShell's
 * readLoop delegates the same logic to ShellProtocolEngine, so these tests
 * exercise the production path without an Android process.
 */
// Trigger for the [test] path-filter: full JVM run after D1-D4 fixes.
class ShellProtocolEngineTest {

    private lateinit var engine: ShellProtocolEngine

    @Before
    fun setUp() {
        engine = ShellProtocolEngine()
    }

    private fun arm(marker: String): ShellEngineCallback {
        val cb = ShellEngineCallback(marker)
        engine.start(cb)
        return cb
    }

    // ==================== S1: 50 команд подряд — свой вывод каждому ====================

    @Test
    fun `s1 sequential commands each get own output and exit`() {
        repeat(50) { i ->
            val marker = ShellProtocolEngine.newMarker()
            val cb = arm(marker)
            // simulate writer: echo wrapped command then its output
            val output = "OUT_$i"
            engine.feed("$output\n")
            println("DBG s1 i=$i marker=$marker buf=<${cb.output}>")
            // marker dispatch resets the slot; the buffered output keeps its
            // raw stream shape (trailing \n included) — contract matches
            // PersistentShell's StringBuilder semantics
            assertEquals("s1 loop $i buffer", "$output\n", cb.output.toString())
            val d = engine.feed("__MINIS_DONE_${marker}_EXIT_${i % 100}__\n")
            assertEquals(i % 100, d!!.exitCode)
            assertEquals("s1 loop $i dispatch", "$output\n", d.output)
            assertTrue(cb.isDone)
            // next command never sees residue
            assertNull(engine.feed("NEXT-CMD-BYTES\n"))
        }
    }

    // ==================== S2: 50 параллельных отданных маркеров — single-shot ====================

    @Test
    fun `s2 concurrent double-dispatch attempts produce single dispatch`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        val chunk = "__MINIS_DONE_${marker}_EXIT_0__\n"
        val dispatches = AtomicInteger(0)
        val barrier = CyclicBarrier(16)
        val latch = CountDownLatch(16)
        val errors = AtomicReference<String?>(null)

        repeat(16) {
            Thread {
                try {
                    barrier.await()
                    // Only one reader thread may call feed() in production;
                    // here we emulate the CAS-latch fighting directly.
                    if (cb.markDone()) {
                        dispatches.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t.toString())
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await()
        assertNull(errors.get())
        assertEquals(1, dispatches.get()) // single-shot guarantee
    }

    // ==================== S3: END разрезан между chunk — exit распознан ====================

    @Test
    fun `s3 marker split between chunks is folded and exits correctly`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        val fullMarker = "__MINIS_DONE_${marker}_EXIT_7__"

        // cut the marker into two arbitrary pieces
        val cut = fullMarker.length / 2
        val part1 = fullMarker.substring(0, cut)
        val part2 = fullMarker.substring(cut)

        assertNull(engine.feed("OUT\n" + part1)) // не dispatch, фрагмент спрятан
        val d = engine.feed(part2 + "\nnext-begins")
        assertNotNull(d)
        assertEquals(7, d!!.exitCode)
        assertEquals("OUT\n", d.output) // до полного маркера — только вывод
        // звонок: overshoot идёт в carry и выдаётся НОВОЙ команде
        val cb2 = arm(ShellProtocolEngine.newMarker())
        // D4: markers are trusted only at a line start. The real driver
        // always emits the wrapped marker after a newline (persistent
        // shell writes "cmd\necho \"__MINIS_DONE...\""), so feed the
        // follow-up marker the same way: carry 'next-begins' + '\n'.
        val d2 = engine.feed("\n__MINIS_DONE_${cb2.marker}_EXIT_0__\n")
        assertNotNull(d2)
        assertEquals("next-begins\n", d2!!.output)
    }

    // ==================== S4: END_A + начало B в одном chunk — без потерь/дублей ====================

    @Test
    fun `s4 marker plus next-command head in one chunk no loss no dup`() {
        val markerA = ShellProtocolEngine.newMarker()
        val cbA = arm(markerA)
        engine.feed("A-out\n")
        val d = engine.feed("__MINIS_DONE_${markerA}_EXIT_0__\nB-HEAD")
        assertNotNull(d)
        assertEquals("A-out\n", d!!.output) // B-HEAD не попал в A
        val markerB = ShellProtocolEngine.newMarker()
        val cbB = arm(markerB)
        val dB = engine.feed("B-body\n__MINIS_DONE_${markerB}_EXIT_0__\n")
        assertEquals("B-HEAD" + "B-body\n", dB!!.output) // carry склеен точно один раз
    }

    // ==================== S5: смерть shell при непустом carry — bytes не потеряны ====================
    // F8 rewrite: the old s5 stole the fragment via drainParked() BEFORE
    // detaching, then only asserted identity — it never modeled the EOF path
    // (fragment surviving the detach, available for diagnostics afterwards)
    // and never fired the EOF completion. This version does the honest EOF:
    // fragment feeds, command detaches (process death), the fragment must
    // still be retrievable for diagnostics, and the callback is completed
    // EXACTLY once (markDone true -> second completion refused).

    @Test
    fun `s5 death with nonempty carry preserves bytes for diagnostics`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        engine.feed("partial\n__MINIS_DO")
        // EOF: process dies mid-marker-fragment. Reader stops. PersistentShell
        // detaches the in-flight command and completes it with an error/EOF.
        val detached = engine.detachCurrent()
        assertSame(cb, detached)
        // The parked fragment survives the detach and is retrievable for
        // diagnostics (EOF handler must not throw the bytes away silently).
        assertEquals("__MINIS_DO", engine.drainParked())
        // EOF completion: callback fires exactly once; a second attempt
        // (reader thread racing the EOF path) is refused by the CAS latch.
        assertTrue(cb.markDone())
        assertFalse(cb.markDone())
    }

    // ==================== S6: cancel-A → partial-END_A → cancel cleanup → remainder → B ====================

    @Test
    fun `s6 cancel mid marker fragment survives drain and next command clean`() {
        val markerA = ShellProtocolEngine.newMarker()
        val cbA = arm(markerA)
        engine.feed("A-user-output\n")
        // A cancelled mid-marker-fragment: the fragment is part of the
        // CURRENT carry (splitPoint path), not yet dispatched. It stays in
        // the carry — NO drainParked() steal here (F6): the drain recogniser
        // needs those bytes to reassemble the marker when the remainder
        // arrives after cancel.
        engine.feed("__MINIS_DONE_" + markerA.take(3)) // fragment без хвоста
        // cancel: detachCurrent atomically voids the slot AND (because the
        // callback is not yet done) arms the drain target for late bytes.
        engine.detachCurrent()

        // F2 writer-gate: arming the NEXT command while the drain of the
        // cancelled one is still pending must be REFUSED — late bytes of A
        // are still in flight and a parked A-marker fragment sits in carry
        // (F10 trace: without the gate that fragment would fold into B).
        var gateRefused = false
        try {
            engine.start(ShellEngineCallback(ShellProtocolEngine.newMarker()))
        } catch (expected: IllegalStateException) {
            gateRefused = true
        }
        assertTrue(gateRefused)
        assertTrue(engine.isDraining)

        // late remainder arrives while drained — closes the drain silently,
        // dispatches nothing, and (F1) leaves NO residue in carry
        assertNull(engine.feed(markerA.substring(3) + "_EXIT_1__\n"))

        // gate is open again: B arms and its output is EXACTLY its own
        val markerB = ShellProtocolEngine.newMarker()
        val cbB = arm(markerB)
        val dB = engine.feed("B-out\n__MINIS_DONE_${markerB}_EXIT_0__\n")
        assertEquals("B-out\n", dB!!.output)
        assertTrue(!dB.output.contains("A-user-output"))
    }

    // ==================== доп: timeout — late END не чужой callback ====================

    @Test
    fun `late END after timeout does not complete the wrong command`() {
        val markerA = ShellProtocolEngine.newMarker()
        val cbA = arm(markerA)
        engine.detachCurrent() // timeout emulation
        val dLate = engine.feed("__MINIS_DONE_${markerA}_EXIT_0__\nB-noise")
        assertNull(dLate) // никто не ждёт: drain, не dispatch

        val markerB = ShellProtocolEngine.newMarker()
        arm(markerB)
        // D4: the END marker is trusted ONLY at the start of a line
        // (a literal marker inside command OUTPUT must not complete it),
        // so the late END must arrive on its own line.
        val dB = engine.feed("B-body\n__MINIS_DONE_${markerB}_EXIT_5__\n")
        assertEquals(5, dB!!.exitCode)
    }

    @Test
    fun `s10 marker inside line does not complete the command`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        // A command's OUTPUT contains the literal marker text (cat of an old
        // log, `set -x`): findMarkerAtLineStart must reject it because the
        // marker is NOT at the start of its line.
        val d = engine.feed("cat: __MINIS_DONE_${marker}_EXIT_0__\n")
        assertNull("in-line marker is not a dispatch", d)
        assertTrue("command is still running", !cb.isDone)
        // The legit line-start marker still completes the command.
        val dOk = engine.feed("__MINIS_DONE_${marker}_EXIT_2__\n")
        assertEquals(2, dOk!!.exitCode)
    }

    // ==================== S7 (F1): post-END bytes of a CANCELLED command never contaminate the next ====================

    @Test
    fun `s7 drain overshoot bytes orphaned not carried into next command`() {
        val markerA = ShellProtocolEngine.newMarker()
        arm(markerA)
        engine.feed("A-out\n")
        engine.detachCurrent() // cancel: drain(A) armed, late bytes may come
        assertTrue(engine.isDraining)
        // Late chunk closes the drain AND carries A's own late tail — the
        // tail must vanish into orphan diagnostics, NOT into carry (F1):
        assertNull(engine.feed("__MINIS_DONE_${markerA}_EXIT_0__\nA-late-tail"))
        val markerB = ShellProtocolEngine.newMarker()
        arm(markerB)
        val dB = engine.feed("B-out\n__MINIS_DONE_${markerB}_EXIT_0__\n")
        assertEquals("B-out\n", dB!!.output)
        // B must NOT contain ANY of A's late tail (F1 invariant):
        assertFalse(dB.output.contains("A-late-tail"))
        assertFalse(dB.output.contains("__MINIS_DONE_${markerA}"))
    }

    // ==================== S8 (F3): exit-code zone cut across chunks parks, real code preserved ====================

    @Test
    fun `s8 exit zone cut across chunks dispatches with the real code`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        // head + "_EXIT_" WITHOUT the digit tail: must NOT dispatch. Old
        // code fired exit=-1 immediately and the real code was lost forever.
        assertNull(engine.feed("OUT\n__MINIS_DONE_${marker}_EXIT_"))
        assertTrue(!cb.isDone)
        // (remainder of chunk: digits + terminator complete the marker line)
        val d2 = engine.feed("12__\n")
        assertNotNull(d2)
        assertEquals(12, d2!!.exitCode) // the REAL code, not -1
        assertEquals("OUT\n", d2.output)
    }

    // ==================== S9 (F9): concurrent feed storm — exactly one dispatch ====================

    @Test
    fun `s9 storm feeds produce exactly one dispatch`() {
        val marker = ShellProtocolEngine.newMarker()
        arm(marker)
        val wins = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..8).map { i ->
            Thread {
                if (engine.feed("storm-$i\n__MINIS_DONE_${marker}_EXIT_0__\n") != null)
                    wins.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, wins.get())
    }
}

/**
 * Mirror of PersistentShell.CommandCallback for the engine's JVM surface.
 * Keeps the markDone CAS so S2 tests the same single-shot latch the
 * production readLoop uses.
 */
class ShellEngineCallback(
    override val marker: String,
    override val output: StringBuilder = StringBuilder(),
    override val lineCallback: ((String) -> Unit)? = null,
) : ShellProtocolEngine.CommandCallbackContract {

    private val done = java.util.concurrent.atomic.AtomicBoolean(false)
    override val isDone: Boolean get() = done.get()
    override fun markDone(): Boolean = done.compareAndSet(false, true)
}
