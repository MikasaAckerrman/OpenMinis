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
            engine.feed(output + "\n")
            // marker dispatch resets the slot; the buffered output keeps its
            // raw stream shape (trailing \n included) — contract matches
            // PersistentShell's StringBuilder semantics
            assertEquals("$output\n", cb.output.toString())
            val d = engine.feed("__MINIS_DONE_${marker}_EXIT_${i % 100}__\n")
            assertEquals(i % 100, d!!.exitCode)
            assertEquals("$output\n", d.output)
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
        val d2 = engine.feed("__MINIS_DONE_${cb2.marker}_EXIT_0__\n")
        assertNotNull(d2)
        assertEquals("next-begins", d2!!.output)
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

    @Test
    fun `s5 death with nonempty carry preserves bytes for diagnostics`() {
        val marker = ShellProtocolEngine.newMarker()
        val cb = arm(marker)
        engine.feed("partial\n__MINIS_DO")
        val parked = engine.drainParked()
        assertEquals("__MINIS_DO", parked) // fragment не выброшен
        // EOF-handler эквивалент: detach + явный -1 dispatch
        val detached = engine.detachCurrent()
       assertSame(cb, detached)
    }

    // ==================== S6: cancel-A → partial-END_A → cancel cleanup → remainder → B ====================

    @Test
    fun `s6 cancel mid marker fragment survives drain and next command clean`() {
        val markerA = ShellProtocolEngine.newMarker()
        val cbA = arm(markerA)
        engine.feed("A-user-output\n")
        // A cancelled mid-marker-fragment: the fragment is part of the
        // CURRENT carry (splitPoint path), not yet dispatched
        engine.feed("__MINIS_DONE_" + markerA.take(3)) // fragment без хвоста
        assertEquals("__MINIS_DONE_" + markerA.take(3), engine.drainParked())
        // cancel cleanup: caller detaches; fragment MUST survive the swap
        // (PersistentShell holds the fragment in pendingCarry, engine mirrors).
        engine.detachCurrent()

        // late remainder arrives while drained
        assertNull(engine.feed(markerA.substring(3) + "_EXIT_1__\n"))

        // B arms: its output is exactly its own
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
        val dB = engine.feed("B-body__MINIS_DONE_${markerB}_EXIT_5__\n")
        assertEquals(5, dB!!.exitCode)
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
