package com.openminis.app.engine

/**
 * [T-read-only-shell] Conservative read-only classification for shell
 * commands — the WritePolicy PLAN mode uses so the agent can still explore
 * with the shell (ls/cat/grep/...) while anything with write or execution
 * side effects is refused.
 *
 * Rules (deliberately tight — this policy exists to unlock reading, not to
 * prove safety; anything unrecognized is MUTATING):
 *  - the command is split on `;`, `&&`, `||`, `|`, and newlines; EVERY
 *    segment's first token must be in the read-only set;
 *  - any redirection (`>`) or process substitution / command substitution
 *    (`$(`, backtick) anywhere makes the whole command mutating;
 *  - `git` is allowed only with read-only subcommands (status/log/diff/...).
 *
 * [T-read-only-shell-hardening] Deep-review hardening (2026-10-04): heads
 * that EXECUTE or WRITE through their own arguments are refused —
 *  - `find` with -exec/-execdir/-delete/-ok/-fprint/-fls (execution and
 *    destruction primitives);
 *  - `env` with any non-assignment argument (env runs it as a command);
 *  - `sort` with -o (writes a file), `date` with -s/--set (sets the clock),
 *    `hostname` with arguments (sets the hostname);
 *  - `awk`/`man`/`less`/`more` dropped entirely: awk's system()/getline
 *    escapes any static policy, man -P runs an arbitrary pager, pagers
 *    have their own `!`/--log-file escape hatches. cat/head/tail cover
 *    the reading those tools did.
 */
object ReadOnlyShellPolicy : WritePolicy {

    private val READ_ONLY_HEADS = setOf(
        "ls", "cat", "head", "tail", "grep", "rg", "find", "wc",
        "file", "stat", "du", "df", "ps", "pwd", "echo", "printf",
        "which", "whereis", "printenv", "whoami", "id",
        "date", "uname", "hostname", "sort", "uniq", "cut",
        "readlink", "realpath", "dirname", "basename",
        "md5sum", "sha256sum", "cksum", "diff", "comm", "tr",
        "true", "false",
    )

    private val GIT_READ_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "blame", "ls-files",
        "rev-parse", "describe", "shortlog", "reflog", "grep",
    )

    /** find flags that execute or destroy — refuse the whole command. */
    private val FIND_MUTATING_PREFIXES = setOf(
        "-exec", "-execdir", "-ok", "-okdir", "-delete",
        "-fprint", "-fprintf", "-fls",
    )

    /** The `env`/`printenv` probes and bare `VAR=... cmd` prefixes: a leading
     *  `FOO=bar` assignment is skipped so `LC_ALL=C sort` classifies by `sort`. */
    override fun isMutatingCommand(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return true

        // Substitution can execute anything — refuse without parsing deeper.
        if (cmd.contains('$') && cmd.contains('(')) return true
        if (cmd.contains('`')) return true
        // Redirection writes (and `2>`/`&>`): treat any '>' as mutating.
        if (cmd.contains('>')) return true

        // Split into segments; every segment must start with a read head.
        return cmd.split(';', '\n')
            .flatMap { segment -> segment.split("&&", "||", "|") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { segment -> !segmentIsReadOnly(segment) }
    }

    private fun segmentIsReadOnly(segment: String): Boolean {
        val tokens = segment.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        // Skip leading VAR=value environment assignments.
        var i = 0
        while (i < tokens.size && Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(tokens[i])) i++
        if (i >= tokens.size) return true // pure assignments change nothing on disk
        val head = tokens[i]
        val rest = tokens.drop(i + 1)
        if (head == "git") {
            val sub = rest.firstOrNull()?.lowercase() ?: return true
            return sub in GIT_READ_SUBCOMMANDS
        }
        if (head == "find") {
            // Execution/destruction flags anywhere in the arg list refuse.
            if (rest.any { t -> FIND_MUTATING_PREFIXES.any { t.startsWith(it) } }) {
                return false
            }
            return true
        }
        if (head == "env") {
            // [T-read-only-shell-hardening] env is TRANSPARENT: env runs the
            // command that follows its assignments — classify THAT command.
            // `env LC_ALL=C sort x` → read-only (sort); `env rm -rf /` →
            // mutating (rm). Recursion is bounded by the segment length.
            val afterEnv = rest.dropWhile { Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(it) }
            if (afterEnv.isEmpty()) return true // pure assignments/print
            return segmentIsReadOnly(afterEnv.joinToString(" "))
        }
        if (head == "sort") {
            // -o chooses an output FILE (writes it).
            return rest.none { it == "-o" || it.startsWith("--output") }
        }
        if (head == "date") {
            // -s/--set rewrites the system clock.
            return rest.none { it == "-s" || it == "--set" || it.startsWith("-s") && it.length > 2 }
        }
        if (head == "hostname") {
            // bare `hostname` reads; `hostname <name>` sets.
            return rest.isEmpty()
        }
        return head in READ_ONLY_HEADS
    }
}
