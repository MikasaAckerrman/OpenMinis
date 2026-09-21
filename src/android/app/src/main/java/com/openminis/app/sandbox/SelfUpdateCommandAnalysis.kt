package com.openminis.app.sandbox

/**
 * Pure string analysis for [SelfUpdateGuard] — no Android imports, so the
 * logic compiles and runs under plain kotlinc for local proof
 * (see SelfUpdateGuardTest; the project rule: prove locally, the build
 * only confirms what is already proven).
 *
 * DELIBERATELY SYNTACTIC, like DestructiveCommandPolicy: it reads the
 * command line and does not promise to catch every path an APK can take
 * onto the device — a shell script that runs `pm install` inside itself, or
 * a python subprocess call, is invisible here (documented, accepted). What
 * it MUST do is (a) catch every direct invocation, including ones wrapped
 * in sh -c / CLI exec / env prefixes / pipes, and (b) NOT fire on agents
 * merely READING code that mentions installs (grep/cat/sed/echo of this
 * very file), which would otherwise yank the app to the foreground via the
 * relaunch alarm.
 */
object SelfUpdateCommandAnalysis {

    private val INSTALL_HINTS = listOf(
        Regex("""\bpm\s+install\b"""),
        Regex("""\bcmd\s+package\s+install\b"""),
        // Multi-step PackageInstaller shell sessions
        Regex("""\binstall-create\b"""),
        Regex("""\binstall-write\b"""),
        Regex("""\binstall-commit\b"""),
    )

    /** Archive paths an install command can reference. */
    private val APK_PATH = Regex("""(\S+\.(?:apk|apks|xapk|apkm))""", RegexOption.IGNORE_CASE)

    /** True when the command installs (or stages) ANY package. */
    fun isPackageInstall(command: String): Boolean =
        command.lineSequence().flatMap { splitSegments(it) }.any { segmentLooksLikeInstall(it) }

    /** Distinct archive paths referenced by the command. */
    fun extractApkPaths(command: String): List<String> =
        APK_PATH.findAll(command).map { it.groupValues[1] }.distinct().toList()

    /**
     * Split a line into shell segments (commands separated by |, ||, &&, ;)
     * so a reader buried in a pipeline can be judged by ITS first token:
     * `cat x | grep install-create` must not trigger, `cat x | pm install`
     * must.
     */
    private fun splitSegments(line: String): List<String> =
        line.split(Regex("""[|;&]{1,2}""")).filter { it.isNotBlank() }

    /**
     * Shell tools that merely READ text. A hint matched inside their
     * arguments is documentation, not execution: an agent grepping this
     * very file for "install-create" must not schedule a relaunch alarm.
     */
    private val READER_TOOLS = setOf(
        "grep", "rg", "cat", "echo", "sed", "awk", "tail", "head", "less", "more",
        "find", "ls", "diff", "sort", "wc", "vi", "nano", "stat", "file", "strings",
        "which", "type", "printf", "logger",
    )

    /** Wrappers whose first argument belongs to the wrapper, not the command. */
    private val WRAPPERS = setOf("sudo", "nohup", "nice", "env", "command", "xargs", "cd", "timeout")

    private fun segmentLooksLikeInstall(segment: String): Boolean {
        // Strip quotes so `exec "pm install -t x.apk"` tokenizes like
        // `exec pm install -t x.apk` (the inner quoted payload is a real
        // command for sh -c / CLI exec semantics).
        val tokens = segment.replace(Regex("""["']"""), " ")
            .trim().split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        // Skip leading wrappers / env assignments to find the first
        // MEANINGFUL token — the tool this segment actually runs.
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t in WRAPPERS) {
                i++
                if (t == "timeout" && i < tokens.size && tokens[i].matches(Regex("""\d+"""))) i++
                continue
            }
            val envAssignment = t.contains('=') && !t.startsWith("-") &&
                t.substringBefore('=').matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))
            if (envAssignment) {
                i++
                continue
            }
            break
        }
        val firstTool = if (i < tokens.size) tokens[i] else return false
        if (firstTool in READER_TOOLS) return false
        // `sh -c "grep install-create"`: the payload after -c is the real
        // command — apply the same reader rule to it.
        if (firstTool in setOf("sh", "bash", "ash", "dash", "zsh") &&
            i + 2 < tokens.size && tokens[i + 1] == "-c"
        ) {
            if (tokens[i + 2] in READER_TOOLS) return false
        }

        // The hint must appear as pm-install / install-session tokens.
        return tokens.any { it == "pm" || it == "package" || it.startsWith("install-") } &&
            INSTALL_HINTS.any { it.containsMatchIn(segment) }
    }
}
