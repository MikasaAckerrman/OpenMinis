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

    /**
     * [lost-resume] Take the registry entry only when it still belongs to this
     * callback.
     *
     * claimForResume removes the entry, so a caller that removes an entry of a
     * DIFFERENT callback destroys that callback's only chance to resume: the
     * command then waits for a result that never comes. The stop path and the
     * EOF path both ran into this - they removed the entry and stayed silent
     * when it was not theirs. A foreign entry stays armed for its own owner.
     */
    private fun claimForResumeIfSame(
        marker: String,
        cb: ShellProtocolEngine.CommandCallbackContract,
    ): CommandCallback? = synchronized(resumeRegistry) {
        val current = resumeRegistry[marker]
        if (current === cb) {
            resumeRegistry.remove(marker)
            current
        } else {
            null
        }
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
        val armed = claimForResumeIfSame(cb.marker, cb)
        if (armed != null) {
            armed.resume?.invoke(armed.output.toString(), -1)
        }
    }

    // ---------------------------------------------------------------------------
    // Reader loop (moved verbatim from PersistentShell.readLoop).
    // ---------------------------------------------------------------------------
    fun readLoop(p: Process) {
        // D2: raw bytes whose UTF-8 sequence is SPLIT by the 4096-byte
        // buffer boundary used to be decoded per-chunk, producing U+FFFD
        // replacement chars in the middle of multi-byte characters
        // (Cyrillic/emoji). Hold the incomplete tail bytes and prepend
        // them to the next chunk. Declared OUTSIDE the try-block: the
        // EOF path below decodes the leftovers after a reader exception.
        val remainder = java.io.ByteArrayOutputStream()
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                // Find how many TRAILING bytes complete an unterminated
                // multi-byte sequence at the chunk end.
                var end = n
                var i = n - 1
                while (i >= 0 && i >= n - 4) {
                    val b = buffer[i].toInt()
                    if (b and 0xC0 == 0xC0) { // lead byte
                        val len = when {
                            b and 0xE0 == 0xC0 -> 2
                            b and 0xF0 == 0xE0 -> 3
                            b and 0xF8 == 0xF0 -> 4
                            else -> 1
                        }
                        if (n - i < len) end = i // incomplete tail: hold it
                        break
                    }
                    if (b and 0xC0 != 0x80) break // ASCII or standalone
                    i--
                }
                remainder.write(buffer, 0, end)
                val bytes = remainder.toByteArray()
                remainder.reset()
                if (end < n) remainder.write(buffer, end, n - end)
                if (bytes.isEmpty()) continue
                val text = String(bytes, Charsets.UTF_8)

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

        // D2-EOF: leftover accession bytes of a truncated final UTF-8
        // sequence cannot be decoded (the process died mid-character);
        // decode them with REPLACE so they surface as U+FFFD diagnostics
        // instead of being silently dropped by the loop exit.
        if (remainder.size() > 0) {
            val tail = String(remainder.toByteArray(), Charsets.UTF_8)
            if (tail.isNotEmpty()) protocol.feed(tail)
            remainder.reset()
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
            // D3-EOF: a half line parked in pendingLine has no next chunk to
            // complete it; emit it before the death resume so the subscriber
            // still sees the command's last (unterminated) output line.
            protocol.flushOnDeath(cb.lineCallback)
            val armed = claimForResumeIfSame(cb.marker, cb)
            if (armed != null) {
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
        // D3-stop: emit the parked half line BEFORE the death resumes, so
        // the subscriber sees the unterminated tail exactly once (the
        // reader-owner path did not fire because start/stop gates the
        // writer thread here).
        if (cb != null) protocol.flushOnDeath(cb.lineCallback)
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
