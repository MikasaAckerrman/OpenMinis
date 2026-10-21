package com.openminis.app.sandbox

import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

/**
 * [WIRING extraction] Protocol-driving half of the persistent shell, split
 * from PersistentShell so the wiring (marker claim-flow, reader loop, EOF/stop
 * teardown) is directly instantiable on the JVM without Android Context —
 * which is what the integration acceptance tests (01..05) require.
 *
 * SEPARATION OF CONCERNS:
 *  - THIS class owns: the ShellProtocolEngine instance, the resume registry
 *    (atomic per-marker continuation claims — the single-resumer model), the
 *    CommandCallback type, the reader loop that feeds engine + resolves
 *    dispatches, the EOF/stop protocol teardown, and the process/stdinWriter
 *    fields (including the W-3 restart-race guard).
 *  - PersistentShell owns: process SPAWNING (Android Context, RootfsManager,
 *    proot download), the writeMutex + executeCommand suspension frame, the
 *    env-queue, stderr ring, kill executor, steering.
 *
 * MIGRATION INVARIANT: every block below was moved VERBATIM from
 * PersistentShell.kt (commit 4e5bae3) — only field access moved to driver
 * fields and recordStderr became the injected diagnostics sink. Any
 * intentional behaviour difference would be a wiring regression; the
 * PersistentShellProtocolDriverIntegrationTest acceptance suite is the
 * executable proof of equivalence.
 *
 * THREADING (unchanged from PersistentShell):
 *  - readLoop/claim-flow runs on the reader thread exclusively;
 *  - armForResume/dropResume run on the executeCommand coroutine;
 *  - claimAllResumes/dropResumeIfArmed run on stop()/timeout paths;
 *  - the ConcurrentHashMap + synchronized(registry) claims keep the
 *    single-resumer rule under every interleaving of those three parties.
 */
internal class PersistentShellProtocolDriver(
    /** Diagnostic sink (PersistentShell routes this into its stderr ring). */
    private val diagnostics: (String) -> Unit,
) {
    val protocol = ShellProtocolEngine()

    // ---------------------------------------------------------------------------
    // Process fields (moved from PersistentShell; same visibility semantics:
    // writers are startProcess (PS) under isStarting CAS, stop() on caller
    // thread, EOF teardown under the W-3 guard below).
    // ---------------------------------------------------------------------------
    @Volatile var process: Process? = null
    @Volatile var stdinWriter: BufferedWriter? = null
    private val TAG = "PersistentShell"

    // ---------------------------------------------------------------------------
    // Resume registry — the single-resumer model (D-2/D-10, wiring).
    // ---------------------------------------------------------------------------
    private val resumeRegistry = ConcurrentHashMap<String, CommandCallback>()

    val currentMarker: String? get() = protocol.marker

    /**
     * [D-2/D-10] Single-shot command callback. `done` is a CAS flag flipped
     * by the engine inside complete(): the Losing path drops silently —
     * resuming the caller's continuation twice is no longer possible, the
     * registry claim (remove) guarantees a single resumer even for races
     * between reader dispatch and stop()/EOF teardown. The protocol-facing
     * side implements ShellProtocolEngine.CommandCallbackContract.
     */
    internal class CommandCallback(
        override val marker: String,
        override val output: StringBuilder = StringBuilder(),
        override val lineCallback: ((String) -> Unit)?,
    ) : ShellProtocolEngine.CommandCallbackContract {
        /** Resumes the suspended executeCommand with (output, exitCode).
         *  Called ONLY by the single markDone() winner → single resume by
         *  construction (D-2/D-10). */
        @Volatile var resume: ((String, Int) -> Unit)? = null
        private val done = AtomicBoolean(false)
        override fun markDone(): Boolean = done.compareAndSet(false, true)
        override val isDone: Boolean get() = done.get()
    }

    fun createCallback(
        marker: String,
        lineCallback: ((String) -> Unit)?,
    ): CommandCallback = CommandCallback(marker = marker, lineCallback = lineCallback)

    fun armForResume(marker: String, cb: CommandCallback) = synchronized(resumeRegistry) {
        resumeRegistry[marker] = cb
    }

    fun dropResume(marker: String) = synchronized(resumeRegistry) {
        resumeRegistry.remove(marker)
    }

    fun claimForResume(marker: String): CommandCallback? = synchronized(resumeRegistry) {
        resumeRegistry.remove(marker)
    }

    fun claimAllResumes(): List<CommandCallback> = synchronized(resumeRegistry) {
        val out = ArrayList<CommandCallback>(resumeRegistry.size)
        out.addAll(resumeRegistry.values)
        resumeRegistry.clear()
        out
    }

    /**
     * [stop-path] The in-flight callback harvested from the protocol may or
     * may not still be armed in the registry (reader may have claimed it a
     * nanosecond earlier with a real Dispatch). Claim it back: if we win, WE
     * resume death; if the reader won, it resumed with real output and the
     * claim here returns null / a different object — no second resume.
     */
    fun dropResumeIfArmed(cb: ShellProtocolEngine.CommandCallbackContract?) {
        if (cb == null) return
        val armed = claimForResume(cb.marker)
        if (armed != null && armed === cb) {
            armed.resume?.invoke(armed.output.toString(), -1)
        }
    }

    // ---------------------------------------------------------------------------
    // Reader loop (moved verbatim from PersistentShell.readLoop).
    // ---------------------------------------------------------------------------
    fun readLoop(p: Process) {
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n, Charsets.UTF_8)

                // [WIRING] The ENTIRE protocol decision for this chunk —
                // carry folding, marker split detection, exit-zone parking,
                // drain consumption, dispatch, orphan routing — happens in
                // the engine's tested feed(); the shell only moves bytes and
                // fires the single completion path.
                val dispatch = protocol.feed(text)

                // [WIRING orphans] Protocol violations and drain leftovers
                // accumulate in the engine's orphan log; mirror whatever is
                // there into the diagnostics sink (post-mortem) and compact —
                // the engine then accumulates from zero, so the next non-null
                // log is by construction NEW bytes only.
                protocol.orphanLog?.let { log ->
                    if (log.isNotBlank()) {
                        for (line in log.lines()) {
                            if (line.isNotBlank()) diagnostics("orphan: $line")
                        }
                        protocol.compactOrphanLog()
                    }
                }

                // [D-2/D-10] Resume ownership: the ENGINE flipped markDone()
                // inside complete() (single CAS, dispatch funnel). Here the
                // reader resolves the Dispatch to its suspended caller via
                // the ATOMIC registry claim — claimForResume is the one and
                // only resume gate; a racing stop()/EOF detach finds the
                // registry entry already gone and resumes nothing.
                if (dispatch != null) {
                    claimForResume(dispatch.marker)?.let { cb ->
                        cb.resume?.invoke(dispatch.output, dispatch.exitCode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // [D-2/D-10, WIRING] Process exited: protocol teardown — parked
        // fragments to diagnostics, single-resumer registry claim below.
        // [WIRING D-2/D-10] Single-resumer rule: whoever owns the registry
        // entry (atomic remove) resumes. detachCurrent harvests the protocol
        // slot; then we claim OUR OWN marker back — if a racing reader
        // already claimed it with a real Dispatch, claim returns null here
        // and the reader resumes; otherwise we own it and resume death.
        protocol.drainParked()?.let { parked -> diagnostics("orphan-at-eof: $parked") }
        protocol.detachCurrent()?.let { cb ->
            val armed = claimForResume(cb.marker)
            if (armed != null && armed === cb) {
                armed.resume?.invoke(armed.output.toString(), -1)
            }
        }
        // Reader is dead: clear protocol epoch (carry/drain) so a RESTARTED
        // shell begins clean instead of inheriting stale fragments.
        protocol.reset()
        // [W-3, review③] THE RESTART RACE: this teardown runs on the reader
        // thread of the DEAD process. Meanwhile ensureStarted() may have
        // already spawned a NEW process into these fields (process=p_new at
        // startup). Nulling unconditionally would kill the live shell's
        // bookkeeping (isAlive=false forever, steerInput dead). Only clear
        // the fields if they still belong to THIS reader's process.
        if (process === p) {
            process = null
            stdinWriter = null
        }
        Log.i(TAG, "Persistent shell process exited")
    }

    // ---------------------------------------------------------------------------
    // Stop-path protocol teardown (first half of PersistentShell.stop(); the
    // process KILL half stays in PersistentShell on its kill executor).
    // ---------------------------------------------------------------------------
    fun teardownForStop() {
        // [v2.3, WIRING] Protocol teardown runs through the engine: parked
        // drain fragments land in diagnostics (never silently dropped), the
        // in-flight callback is claimed and completed ONCE via CAS — racing
        // reader dispatch for the same marker loses silently.
        protocol.drainParked()?.let { carry ->
            if (carry.isNotBlank()) diagnostics("[carry-dropped] $carry")
        }
        val cb = protocol.detachCurrent()
        protocol.reset()
        // [WIRING stop] Registry harvest: every ARMED waiter gets death (-1)
        // exactly once. Blind clear() would orphan an entry the reader had
        // already claimed-but-not-yet-resumed; the harvest-then-claim lock
        // cycle hands each pending resume to exactly one owner.
        claimAllResumes().forEach { cb ->
            cb.resume?.invoke(cb.output.toString(), -1)
        }
        dropResumeIfArmed(cb)
    }
}
