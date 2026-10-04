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
 */
object ReadOnlyShellPolicy : WritePolicy {

    private val READ_ONLY_HEADS = setOf(
        "ls", "cat", "head", "tail", "grep", "rg", "find", "wc",
        "file", "stat", "du", "df", "ps", "pwd", "echo", "printf",
        "which", "whereis", "env", "printenv", "whoami", "id",
        "date", "uname", "hostname", "sort", "uniq", "cut", "awk",
        "less", "more", "man", "readlink", "realpath", "dirname", "basename",
        "md5sum", "sha256sum", "cksum", "diff", "comm", "tr",
        "true", "false",
    )

    private val GIT_READ_SUBCOMMANDS = setOf(
        "status", "log", "diff", "show", "blame", "ls-files",
        "rev-parse", "describe", "shortlog", "reflog", "grep",
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
        if (head == "git") {
            val sub = tokens.getOrNull(i + 1)?.lowercase() ?: return true
            return sub in GIT_READ_SUBCOMMANDS
        }
        return head in READ_ONLY_HEADS
    }
}
