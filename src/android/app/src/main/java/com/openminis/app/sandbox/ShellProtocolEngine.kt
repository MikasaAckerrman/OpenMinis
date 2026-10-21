package com.openminis.app.sandbox

import java.util.UUID

/**
 * Pure protocol engine for the persistent-shell END-marker handshake —
 * extracted from [PersistentShell] so the D-1/D-2/D-3/D-10 acceptance
 * scenarios run as plain JVM tests (PersistentShell itself needs Android
 * Context for its process wiring, and CI runs JVM-only unit tests).
 *
 * Owns exactly three concerns (mirrors REVIEW_vc144_vault_chain.md v2.3):
 *
 *  1. [feed]  — chunk ingestion: fold pending carry, claim the in-flight
 *               command atomically, locate the END marker (complete or cut),
 *               route bytes to output vs carry, and produce at most one
 *               dispatch per chunk.
 *  2. [start] — command admission: generates the unique marker + wrapped
 *               command line. One in-flight command at a time is enforced
 *               by the caller (PersistentShell.writeMutex); the engine only
 *               guards the marker-unique spot.
 *  3. [complete/reset] — single-shot completion latch + cancel-detach.
 *
 * NOT thread-safe by itself EXCEPT that feed() must run on the single
 * reader thread and complete()/cancel() may race from any thread: the CAS
 * in [markDone] decides the winner. All other state is reader-confined.
 */
internal class ShellProtocolEngine {

    /** Marker template (matches PersistentShell's echo line). */
    companion object {
        const val MARKER_PREFIX = "__MINIS_DONE_"
        const val MARKER_SUFFIX = "_EXIT_"
        const val MARKER_END = "__"

        /** Unique marker per command (PersistentShell passes the same value). */
        fun newMarker(): String = UUID.randomUUID().toString().take(8)
    }

    /**
     * Claimed in-flight command. Read by the reader thread ([feed]); written
     * by [start] from a caller coroutine and nulled by [detachCurrent] from
     * a different coroutine on timeout/cancel/EOF. Cross-thread visibility
     * is MANDATORY (F4): without @Volatile the reader could observe a stale
     * null [drainMarker] and route a late END into the orphan path forever,
     * breaking v2.3 Invariant-C drain closure.
     */
    @Volatile
    private var current: CommandCallbackContract? = null

    /**
     * Marker of a DETACHED-but-still-running command (cancel/timeout): until
     * its END line is recognized, every chunk belongs to the drain path —
     * the bytes are consumed silently (orphan log) and may NEVER contaminate
     * the next command's output (v2.3 invariant C).
     * Null = no drain pending. Cleared only when the drain marker's END line
     * is fully matched (or on [reset]/hard teardown).
     * @Volatile for the same cross-thread reason as [current] (F4).
     */
    @Volatile
    private var drainMarker: String? = null

    /** User-visible leftovers (drain-routed) — diagnostics only. */
    var orphanLog: String? = null
        private set

    /**
     * [D-3] bytes pushed back: post-marker overshoot (belongs to the NEXT
     * command) or a marker fragment cut at the chunk boundary (belongs to
     * the CURRENT command). Exactly one carry chain — the caller serializes
     * command starts.
     */
    // Thread-confined to the reader (feed/drainFold) + teardown
    // (drainParked/reset) — see reset() note.
    private var carry: String? = null

    val marker: String? get() = current?.marker  // contract getter

    /** True while a cancelled/detached command's drain is pending (F2 gate):
     *  callers use it to decide whether to wait, spin-feed, or fail fast. */
    val isDraining: Boolean get() = drainMarker != null

    /**
     * Feed a reader chunk. Returns a [Dispatch] when the fed bytes completed
     * the in-flight command (exactly once per command), null otherwise.
     *
     * Semantics:
     *  - carry is folded in front (split-marker case);
     *  - if a live command exists and its marker fully appears: bytes before
     *    go to its buffer, exit code parsed, overshoot after the marker line
     *    becomes the new carry, dispatch is produced;
     *  - if no marker: bytes go to the buffer, except a trailing fragment
     *    that is a PREFIX of the marker — parked as carry [D-3b];
     *  - if NO live command and a drain is pending: bytes fold into the drain
     *    path (orphan log) until the drain marker closes [v2.3 Invariant-C];
     *  - if NO live command and NO drain: idle-gap bytes are logged as
     *    orphans (stderr diagnostics) — acccf94 semantics; they are never
     *    handed to a future command.
     */
    fun feed(chunk: String): Dispatch? {
        // v2.3-C drain: a cancelled command's late END marker and its residual
        // bytes are consumed HERE (orphan), never handed to a future command.
        val dm = drainMarker
        if (current == null && dm != null) {
            val drained = drainFold(chunk, dm)
            if (drained) drainMarker = null
            return null
        }
        return feedLive(chunk)
    }

    /** Drain-path fold: append to buffer, look for END of the drain marker.
     *  Bytes up to the END line → orphan log; bytes after its line-end are
     *  orphaned TOO (F1: they belong to the cancelled command's tail and must
     *  never fold into the next command). Returns true iff the drain
     *  completed (END recognized in this fold). */
    private fun drainFold(chunk: String, dm: String): Boolean {
        val acc = (carry ?: "") + chunk
        carry = null
        val head = MARKER_PREFIX + dm + MARKER_SUFFIX
        val idx = acc.indexOf(head)
        if (idx < 0) {
            // Keep only a possible trailing fragment of the drain marker; the
            // rest is orphaned user output — never contaminates the owner.
            val sp = splitPoint(acc, dm)
            if (sp < acc.length) {
                orphanAccumulate(acc.substring(0, sp))
                carry = acc.substring(sp)
            } else {
                orphanAccumulate(acc)
            }
            return false
        }
        // END line found: bytes before it → orphan. Bytes AFTER its line-end
        // (F1, v2.3 Invariant-C): still produced by the CANCELLED command's
        // process group — they may be late children flushing after the marker.
        // They are orphaned to diagnostics and NEVER become carry: carry folds
        // into the next command's buffer, which would contaminate it.
        val lineEnd = acc.indexOf('\n', idx)
        orphanAccumulate(acc.substring(0, if (lineEnd < 0) acc.length else lineEnd + 1))
        if (lineEnd >= 0 && lineEnd + 1 < acc.length) {
            orphanAccumulate(acc.substring(lineEnd + 1))
        }
        return true
    }

    private fun orphanAccumulate(s: String) {
        orphanLog = ((orphanLog ?: "") + s).takeLast(8192)
    }

    private fun feedLive(chunk: String): Dispatch? {
        // Idle-gap bytes (current==null, no drain): these are not shared-chunk
        // overshoot of a just-dispatched END — they arrived on their own and
        // belong to nobody's buffer. They go to the orphan log; the NEXT
        // command must NOT inherit them (verified by s1: 'NEXT-CMD-BYTES'
        // must never reach buffer of the next armed command). Only
        // same-chunk overshoot after a dispatch (stored in carry by the
        // marker branch below) is foldable by the next owner.
        val cb = current
        if (cb == null) {
            orphanAccumulate(chunk)
            return null
        }
        var text = (carry ?: "") + chunk
        carry = null

        val head = MARKER_PREFIX + cb.marker + MARKER_SUFFIX
        val idx = text.indexOf(head)

        if (idx >= 0) {
            // F3: the marker HEAD is present but the exit-code zone may still
            // be split across chunk boundaries ("__MINIS_DONE_m_EXIT_" without
            // the trailing digits+"__"). Dispatching now would parse exit -1
            // and the true code would be lost. The marker line must END with
            // '\n' inside THIS fold; otherwise park the whole marker-part as
            // carry and wait for the remainder (symmetric with the split-head
            // case below).
            val lineEnd = text.indexOf('\n', idx)
            if (lineEnd < 0) {
                cb.output.append(text.substring(0, idx))
                takeIfCarrying(text.substring(idx))
                return null
            }
            val before = text.substring(0, idx)
            cb.output.append(before)
            val afterMarker = text.substring(idx)
            val exitCode = parseExitCode(afterMarker, cb.marker)
            if (lineEnd + 1 < text.length) {
                // Overshoot belongs to a FUTURE command.
                takeIfCarrying(text.substring(lineEnd + 1))
            }
            return complete(cb, exitCode)
        }

        // No complete marker: park a trailing marker-prefix fragment [D-3b].
        val split = splitPoint(text, cb.marker)
        val visible = if (split < text.length) {
            takeIfCarrying(text.substring(split))
            text.substring(0, split)
        } else {
            text
        }
        cb.output.append(visible)
        feedLines(visible, cb.lineCallback)
        return null
    }

    /** Arm a command (caller installed it atomically in PersistentShell).
     *  Writer-gate (F2, v2.3 Invariant-C): while a cancelled command's drain
     *  is pending, its late bytes may still arrive — arming a new command now
     *  would let them contaminate (or worse, a parked fragment of the DRAIN
     *  marker itself would fold into the new command's buffer, F10). The
     *  caller must wait for the drain to close (feed until it returns past
     *  the drain, or tear the shell down). */
    fun start(callback: CommandCallbackContract) {
        check(current == null) { "command already in flight" }
        check(!callback.isDone) { "callback already completed" }
        check(drainMarker == null) {
            "drain pending for cancelled command (late output may still arrive); " +
                "arm refused — wait for drain closure or stop the shell"
        }
        current = callback
        // carry is NOT cleared here: byte-carry (post-normal-END overshoot)
        // belongs to the NEXT command per v2.3 invariant D — its bytes merge
        // into the new owner's buffer at the next fold. This is safe ONLY
        // because the writer-gate above guarantees no drain is pending, so a
        // surviving carry cannot contain foreign bytes (F10): post-drain
        // leftovers never reach this point, they are orphaned in drainFold.
    }

    /** Detach after timeout/cancel (PersistentShell's CAS-detach path).
     *  Marks a drain: leftover fragments + late output of [callback] must
     *  be routed to the orphan log, not to the next command (v2.3 C). */
    fun detachCurrent(): CommandCallbackContract? {
        val old = current ?: return null
        current = null
        if (!old.isDone) {
            drainMarker = old.marker
        }
        return old
    }

    /** Bytes parked while draining (diagnostics only). */
    fun drainParked(): String? {
        val old = carry
        carry = null
        return old
    }

    /**
     * [wiring] Hard protocol reset for shell-epoch teardown (stop()).
     * Called only after detachCurrent() harvested the in-flight callback:
     * clears drain + carry so a RESTARTED shell (same PersistentShell
     * instance, new process) begins from a clean protocol state instead of
     * inheriting a stale drain marker. Orphan log is preserved — it is
     * read-only diagnostics.
     */
    fun reset() {
        // Note: carry/drainMarker are reader-thread-written in normal opera-
        // tion; reset() runs from stop()/EOF teardown where the reader is
        // dying or dead, and the next shell epoch starts from a fresh feed.
        // current/drainMarker are @Volatile (F4); plain carry is safe here
        // because only drainParked()/reset() touch it cross-thread, both
        // from the same teardown path.
        drainMarker = null
        carry = null
    }

    /** [wiring] Caller mirrored the accumulated orphan log — start a fresh
     *  accumulation window so the next mirror reports only NEW entries. */
    fun compactOrphanLog() {
        orphanLog = null
    }

    /** Single-shot completion via CAS; returns the Dispatch iff this wins. */
    private fun complete(cb: CommandCallbackContract, exitCode: Int): Dispatch? {
        if (!cb.markDone()) return null
        // Lifecycle: a dispatched command no longer owns the slot — the next
        // winner may start immediately (test harness arms B right after A's
        // dispatch, mirroring the PersistentShell writeMutex flow).
        if (current === cb) current = null
        return Dispatch(cb.marker, cb.output.toString(), exitCode)
    }

    /** Reader-confined feedLines (D-3a fixed): a trailing partial line is NOT
     *  emitted here — it stays in the output buffer and is emitted together
     *  with the newline that completes it (or with the marker dispatch).
     *  Line callbacks therefore never see the same line twice. */
    private fun feedLines(text: String, lineCallback: ((String) -> Unit)?) {
        if (lineCallback == null || text.isEmpty()) return
        val completeText = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', "")
        if (completeText.isEmpty()) return
        val lines = completeText.split('\n')
        for (i in 0 until lines.size - 1) { // complete lines only (skip empty tail after final \n)
            lineCallback(lines[i].trimEnd('\r'))
        }
    }

    private fun takeIfCarrying(bytes: String?) {
        if (!bytes.isNullOrEmpty()) carry = bytes
    }

    private fun parseExitCode(afterMarker: String, marker: String): Int {
        val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
        val match = regex.find(afterMarker)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * [D-3b] Index of a marker-PREFIX fragment at the tail (largest first).
     * Returns text.length when no fragment exists. Streamed echo writes make
     * chunk-edge cuts the only real split case.
     */
    private fun splitPoint(text: String, marker: String): Int {
        val full = MARKER_PREFIX + marker + MARKER_SUFFIX
        val maxLen = minOf(full.length, text.length)
        for (len in maxLen downTo 1) {
            var isPrefix = true
            for (i in 0 until len) {
                if (text[text.length - len + i] != full[i]) { isPrefix = false; break }
            }
            if (isPrefix) return text.length - len
        }
        return text.length
    }

    data class Dispatch(val marker: String, val output: String, val exitCode: Int)

    /** Contract surface for the shell's concrete callback (and the JUnit
     *  harness's [ShellEngineCallback] in the test source set). */
    interface CommandCallbackContract {
        val marker: String
        val output: StringBuilder
        val lineCallback: ((String) -> Unit)?
        val isDone: Boolean

        /**
         * Exactly-once CAS completion gate (F2/F5/D-2/D-10). All completion
         * paths — dispatch, EOF, stop, timeout-emulation — funnel through
         * here; the loser of the race silently skips dispatch/resume.
         */
        fun markDone(): Boolean

    }
}
