package com.openminis.app.engine

/**
 * [T-reasoning-degeneracy] A streaming detector for a reasoning that fell
 * into a loop.
 *
 * ## Why the window detector was not enough
 *
 * [ReasoningDegeneracy.detect] judges the last [ReasoningDegeneracy.WINDOW_CHARS]
 * characters. A flood in the middle of a long reasoning is therefore
 * invisible: it is either diluted below the coverage threshold, or it has
 * already scrolled out of the window before anybody looked. The measured
 * loop carried zero signs in its last 512 characters, so the barrier could
 * not fire and the model kept writing arrows until the end of the turn.
 *
 * ## What this watch keeps
 *
 * Evidence instead of a window:
 *
 *  - every sign is counted for the whole stream, so a flood that happened
 *    in the middle is still provable after the tail moved on;
 *  - every line is counted for the whole stream, so a line that repeats
 *    three times is a loop wherever it repeats;
 *  - the longest run of one sign is kept, so a compact burst is caught
 *    even when it is short;
 *  - the short window is still maintained, so a dense burst is caught as
 *    early as before.
 *
 * The cost is O(1) per character, so the barrier can run on every delta
 * without copying the buffer.
 */
internal class ReasoningLoopWatch(
    private val minSignOccurrences: Int = ReasoningDegeneracy.MIN_SIGN_OCCURRENCES,
    private val minSignCoverage: Float = ReasoningDegeneracy.MIN_SIGN_COVERAGE,
    private val minLineOccurrences: Int = ReasoningDegeneracy.MIN_LINE_OCCURRENCES,
    private val minLineLength: Int = ReasoningDegeneracy.MIN_LINE_LENGTH,
    private val windowChars: Int = ReasoningDegeneracy.WINDOW_CHARS,
    /**
     * How much of the reasoning a repeated line has to take before it
     * counts as a loop. A line that shows up three times inside a long
     * reasoning is normal text: a repeated header, a repeated checklist
     * item, the same sentence in two sections.
     */
    private val minLineCoverage: Float = 0.04f,
    /**
     * A repeated line has to carry meaning. A short repeated line is
     * formatting - a header, a separator, a label - and repeats in normal
     * reasoning all the time.
     */
    private val minRepeatedLineLength: Int = 12,
) {

    private var totalChars = 0L
    private var totalLines = 0L

    /** Every sign of the whole stream, not of a window. */
    private val symbolCounts = HashMap<Char, Int>()

    /** Every line of the whole stream, counted by its trimmed text. */
    private val lineCounts = HashMap<String, Int>()

    /** The last [windowChars] characters, with their symbol counts. */
    private val window = CharArray(windowChars)
    private var windowPos = 0
    private var windowLen = 0
    private val windowSymbolCounts = HashMap<Char, Int>()

    private var runChar: Char? = null
    private var runLength = 0
    private var maxRun = 0
    private var maxRunChar: Char = ' '

    private val pendingLine = StringBuilder()

    /**
     * Feeds one delta and returns the finding when the stream is already
     * provably a loop.
     */
    fun accept(delta: CharSequence): ReasoningDegeneracy.Finding? {
        if (delta.isEmpty()) return null
        for (ch in delta) {
            totalChars++
            if (ReasoningDegeneracy.isSymbol(ch)) {
                symbolCounts[ch] = (symbolCounts[ch] ?: 0) + 1
                if (ch == runChar) {
                    runLength++
                } else {
                    runChar = ch
                    runLength = 1
                }
                if (runLength > maxRun) {
                    maxRun = runLength
                    maxRunChar = ch
                }
            } else {
                runChar = null
                runLength = 0
            }
            pushWindow(ch)
            if (ch == '\n') {
                countLine()
            } else {
                pendingLine.append(ch)
            }
        }
        return finding()
    }

    /**
     * Counts the line that has no newline at the end and returns the
     * finding. Call it when the stream is over: a line that is still being
     * written must not be counted twice.
     */
    fun finish(): ReasoningDegeneracy.Finding? {
        countLine()
        return finding()
    }

    private fun countLine() {
        val line = pendingLine.toString().trim()
        pendingLine.setLength(0)
        if (line.isEmpty()) return
        totalLines++
        if (line.length < minLineLength) return
        val count = (lineCounts[line] ?: 0) + 1
        lineCounts[line] = count
    }

    private fun pushWindow(ch: Char) {
        if (windowLen == windowChars) {
            val evicted = window[windowPos]
            if (ReasoningDegeneracy.isSymbol(evicted)) {
                val left = (windowSymbolCounts[evicted] ?: 1) - 1
                if (left <= 0) windowSymbolCounts.remove(evicted) else windowSymbolCounts[evicted] = left
            }
        } else {
            windowLen++
        }
        window[windowPos] = ch
        windowPos = (windowPos + 1) % windowChars
        if (ReasoningDegeneracy.isSymbol(ch)) {
            windowSymbolCounts[ch] = (windowSymbolCounts[ch] ?: 0) + 1
        }
    }

    private fun finding(): ReasoningDegeneracy.Finding? {
        // 1. A dense burst right now. The same rule the window detector
        //    used, but on a window that is maintained instead of copied.
        for ((symbol, count) in windowSymbolCounts) {
            if (count < minSignOccurrences) continue
            val coverage = count.toFloat() / windowLen.toFloat()
            if (coverage < minSignCoverage) continue
            return ReasoningDegeneracy.Finding(
                ReasoningDegeneracy.Reason.SIGN_FLOOD,
                symbol.toString(),
                count,
                coverage,
            )
        }
        // 2. The same line, three times, anywhere in the stream. The
        //    window detector looked at the last twelve lines only, so a
        //    line that repeats further apart was never counted.
        var bestLine: String? = null
        var bestCount = 0
        for ((line, count) in lineCounts) {
            if (count < minLineOccurrences) continue
            if (count > bestCount) {
                bestCount = count
                bestLine = line
            }
        }
        if (bestLine != null) {
            val coverage = bestCount.toFloat() / totalLines.toFloat().coerceAtLeast(1f)
            // The coverage was computed but never used, so three identical
            // lines anywhere in the reasoning killed a healthy turn. A real
            // loop fills the stream with the same line, so the share of
            // the stream has to carry the decision. The later rules below
            // must still run when this branch does not fire.
            if (coverage >= minLineCoverage && bestLine.length >= minRepeatedLineLength) {
                return ReasoningDegeneracy.Finding(
                    ReasoningDegeneracy.Reason.REPEATED_LINE,
                    bestLine,
                    bestCount,
                    coverage,
                )
            }
        }
        // 3. A compact run of one sign: short, but still a loop.
        if (maxRun >= minSignOccurrences) {
            return ReasoningDegeneracy.Finding(
                ReasoningDegeneracy.Reason.SIGN_FLOOD,
                maxRunChar.toString(),
                maxRun,
                maxRun.toFloat() / totalChars.toFloat().coerceAtLeast(1f),
            )
        }
        // 4. A sign that fills the reasoning as a whole. This is the flood
        //    the window could not see, because it had already scrolled out
        //    of the tail by the time the barrier looked.
        for ((symbol, count) in symbolCounts) {
            if (count < minSignOccurrences) continue
            val coverage = count.toFloat() / totalChars.toFloat()
            if (coverage < minSignCoverage) continue
            return ReasoningDegeneracy.Finding(
                ReasoningDegeneracy.Reason.SIGN_FLOOD,
                symbol.toString(),
                count,
                coverage,
            )
        }
        return null
    }
}
