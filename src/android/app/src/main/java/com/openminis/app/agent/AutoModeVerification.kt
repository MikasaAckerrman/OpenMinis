package com.openminis.app.agent

/**
 * [T-auto-mode-verify] Machine verification for the autonomous run — the
 * "manager checks, doesn't ask" half of verification-aware planning.
 *
 * Weak pattern (what this replaces): the loop asks the MODEL "done?" and
 * trusts TASK_COMPLETE. Strong pattern (VeriMAP, 2026): every claimed
 * completion carries criteria the ENGINE can execute — files that must
 * exist, files that must not, a command whose exit code is the verdict.
 * The sentinel is only honoured after its criteria PASS; a failing check
 * with TASK_COMPLETE is a rejected claim, not a finish line.
 *
 * Pure by design (no Android, no I/O): the block format, the state machine
 * and the shell quoting are pinned by unit tests; the VM only executes what
 * this object composes.
 */
object AutoModeVerification {

    /** Consecutive verification failures before a replan is forced. */
    const val MAX_FAILS = 3

    /** Total strategy changes before the run gives up honestly. */
    const val MAX_REPLANS = 3

    /** One verification: what the engine checks after the turn. */
    data class Criteria(
        val filesExist: List<String> = emptyList(),
        val filesAbsent: List<String> = emptyList(),
        val command: String? = null,
    ) {
        val isEmpty: Boolean get() = filesExist.isEmpty() && filesAbsent.isEmpty() && command == null
    }

    /** Result of executing the criteria. */
    data class Report(val passed: Boolean, val failures: List<String>)

    /** Run-level transition: retry same approach → change strategy → stop. */
    enum class Step { CONTINUE, RETRY_SAME, REPLAN, DISARM }

    /**
     * Parse the VERIFY block from the turn's final text. Format (each key
     * optional, comma-separated paths, single-line command):
     * ```
     * VERIFY:
     * files: /a.kt, /b.kt
     * absent: /old.kt
     * cmd: ./gradlew test --tests X
     * ```
     * Null when no block is present — a research-only turn legitimately has
     * nothing machine-checkable; absence degrades to the legacy behaviour
     * (trust the sentinel), never blocks the run.
     */
    fun parse(text: String): Criteria? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val start = lines.indexOfFirst { it.equals("VERIFY:", ignoreCase = true) }
        if (start < 0) return null

        val files = mutableListOf<String>()
        val absent = mutableListOf<String>()
        var cmd: String? = null
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val lower = line.lowercase()
            when {
                lower.startsWith("files:") ->
                    files.addAll(splitPaths(line.substring(6)))
                lower.startsWith("files exist:") ->
                    files.addAll(splitPaths(line.substring(12)))
                lower.startsWith("absent:") ->
                    absent.addAll(splitPaths(line.substring(7)))
                lower.startsWith("cmd:") || lower.startsWith("command:") ->
                    cmd = line.substring(line.indexOf(':') + 1).trim().takeIf { it.isNotEmpty() }
                        ?: cmd
                else -> break // unknown line ends the block
            }
        }
        val criteria = Criteria(files, absent, cmd)
        return if (criteria.isEmpty) null else criteria
    }

    private fun splitPaths(raw: String): List<String> =
        raw.split(',').map { it.trim().trim('"', '\'', '`') }.filter { it.isNotEmpty() }

    /**
     * The transition table. [fails] counts consecutive failures of the SAME
     * approach; [replans] counts strategy changes already spent.
     */
    fun nextStep(fails: Int, replans: Int): Step = when {
        fails < MAX_FAILS -> Step.RETRY_SAME
        replans < MAX_REPLANS -> Step.REPLAN
        else -> Step.DISARM
    }

    /** POSIX single-quote escaping for paths we splice into test commands. */
    fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** Single-path existence probe — per-path so a failure names the path. */
    fun existsCommand(path: String): String = "test -e ${shellQuote(path)}"

    /** Single-path absence probe. */
    fun notExistsCommand(path: String): String = "! test -e ${shellQuote(path)}"

    /**
     * Batch probes: ONE shell command (one session-mutex acquisition, one
     * PRoot round-trip) for the whole list — the fast path. `test` is a
     * shell builtin, so long lists are not bounded by exec ARG_MAX either.
     */
    fun allExistCommand(paths: List<String>): String =
        paths.joinToString(" && ") { "test -e ${shellQuote(it)}" }

    fun noneExistCommand(paths: List<String>): String =
        paths.joinToString(" && ") { "! test -e ${shellQuote(it)}" }

    /** Failure report for the next continuation / replan prompt. */
    fun failureReport(failures: List<String>): String =
        if (failures.isEmpty()) "verification passed" else
            failures.joinToString(prefix = "verification FAILED:\n", separator = "\n")
}
