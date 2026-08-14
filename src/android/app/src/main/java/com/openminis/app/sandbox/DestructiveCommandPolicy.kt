package com.openminis.app.sandbox

/**
 * Recognises shell commands that destroy data, so the agent can be asked to
 * confirm instead of being allowed — or blocked — silently.
 *
 * WHY THIS EXISTS. On 2026-08-14 an agent session ran `rm -rf om*` in /tmp
 * meaning to delete one directory. The shell expanded the glob into three
 * (om, om2, omw) and the working tree of a *parallel* session was wiped —
 * twice, the second time three seconds after that session had recreated it.
 * Nothing was lost for good (the commits were on GitHub) but hours of another
 * session's work stalled while it diagnosed a problem that did not exist.
 *
 * WHY A CLASSIFIER AND NOT A BLOCKLIST. A hard blocklist was tried first, in
 * the sandbox, as a wrapper around `rm`. It stopped the accident but it also
 * stops ordinary work: `rm -rf build/`, clearing a temp dir, dropping a stale
 * clone. An agent that cannot delete its own scratch files starts inventing
 * workarounds, which is worse than the original problem. So the decision here
 * is deliberately *three*-valued:
 *
 *   ALLOW    — ordinary file removal. Runs untouched, no prompt, no friction.
 *   CONFIRM  — recursive, glob-expanded, or aimed at a repository. Runs only
 *              after the user says yes. This is the case that caused the
 *              incident, and it is also the case where a human glance is
 *              enough to catch the mistake.
 *   REFUSE   — user data and system roots. No prompt, because there is no
 *              legitimate reason for the agent to be the one deleting them.
 *
 * The classifier is intentionally *syntactic*: it reads the command line, not
 * the filesystem. It cannot know whether /tmp/x is precious; it can know that
 * `rm -rf` with a glob is a shape that has already caused damage once.
 *
 * WHAT IT DOES NOT TRY TO DO. This is not a security boundary. An agent that
 * wants to bypass it can write a Python script that calls os.unlink. The point
 * is to catch the *unintentional* case — the one-character mistake, the glob
 * that matched more than expected — which is what actually happened.
 */
object DestructiveCommandPolicy {

    enum class Verdict { ALLOW, CONFIRM, REFUSE }

    data class Decision(
        val verdict: Verdict,
        /** Short, user-facing reason. Empty for ALLOW. */
        val reason: String = "",
        /** The specific fragment that triggered the decision, for the prompt. */
        val fragment: String = "",
    )

    /**
     * Refused with no exception whatsoever — not even a single file.
     *
     * Memory, skills and mounted folders are irreplaceable in a way projects
     * are not: memory has no remote copy, skills are hand-written, mounts point
     * at the user's own storage outside the sandbox. Losing one file there is
     * silent and permanent.
     *
     * This split was not planned — it came out of testing. The first version
     * allowed "one plain file, no recursion" everywhere under /var/minis, and
     * the test `rm /var/minis/memory/2026-08-14.md` passed as ALLOW. That is
     * exactly how today's memory file was deleted while the wrapper was being
     * verified in the sandbox.
     */
    private val refusedAbsolute = listOf(
        "/var/minis/memory",
        "/var/minis/skills",
        "/var/minis/mounts",
    )

    /**
     * Refused, with one narrow concession: a single plain file, no recursion.
     * Projects live here, and the agent legitimately writes scratch files into
     * the tree it is working in; forbidding their cleanup drives it to invent
     * workarounds. Directories and batches stay refused.
     */
    private val refusedSoft = listOf(
        "/var/minis/shared",
        "/var/minis/attachments",
    )

    /** System roots. `rm -rf /` and friends. */
    private val refusedExact = listOf(
        "/", "/bin", "/sbin", "/lib", "/usr", "/etc", "/var", "/root", "/home",
        "/proc", "/sys", "/dev", "/opt", "/srv", "/var/minis",
    )

    /** Commands that delete or destructively overwrite. */
    private val destructiveTools = setOf(
        "rm", "rmdir", "shred", "unlink", "truncate",
    )

    /** Recursive-delete flags across the tools above. */
    private fun isRecursiveFlag(token: String): Boolean {
        if (!token.startsWith("-") || token.startsWith("--")) {
            return token == "--recursive"
        }
        return token.drop(1).any { it == 'r' || it == 'R' }
    }

    private fun hasGlob(token: String): Boolean =
        token.contains('*') || token.contains('?') ||
            (token.contains('{') && token.contains(','))

    /**
     * Classify a whole command string. The string may contain several commands
     * joined by `;`, `&&`, `||` or newlines; every segment is examined and the
     * strictest verdict wins, because the shell will run all of them.
     */
    fun classify(command: String): Decision {
        var worst = Decision(Verdict.ALLOW)
        for (segment in splitSegments(command)) {
            val d = classifySegment(segment)
            if (d.verdict.ordinal > worst.verdict.ordinal) worst = d
            if (worst.verdict == Verdict.REFUSE) return worst
        }
        return worst
    }

    /** Split on shell separators. Quotes are not parsed: a `;` inside a string
     *  produces one extra harmless segment, which is the safe direction. */
    private fun splitSegments(command: String): List<String> =
        command.split(";", "&&", "||", "|", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun classifySegment(segment: String): Decision {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return Decision(Verdict.ALLOW)

        // Skip leading env assignments and `sudo`-likes so `FOO=1 rm -rf x`
        // is still seen as an rm.
        var i = 0
        while (i < tokens.size &&
            (tokens[i].contains('=') && !tokens[i].startsWith("-") ||
                tokens[i] == "sudo" || tokens[i] == "env" || tokens[i] == "command")
        ) i++
        if (i >= tokens.size) return Decision(Verdict.ALLOW)

        val tool = tokens[i].substringAfterLast('/')
        if (tool !in destructiveTools) {
            // `find … -delete` and `find … -exec rm` delete just as thoroughly.
            if (tool == "find" && tokens.any { it == "-delete" || it == "-exec" }) {
                return Decision(Verdict.CONFIRM,
                    "find удаляет файлы по всему дереву", segment)
            }
            // `git clean -fd` removes untracked files — the ones never committed.
            if (tool == "git" && tokens.contains("clean") &&
                tokens.any { it.startsWith("-") && it.contains('f') }
            ) {
                return Decision(Verdict.CONFIRM,
                    "git clean удаляет незакоммиченные файлы", segment)
            }
            return Decision(Verdict.ALLOW)
        }

        val args = tokens.drop(i + 1)
        val flags = args.filter { it.startsWith("-") && it != "-" }
        val paths = args.filter { !it.startsWith("-") || it == "-" }
        val recursive = flags.any { isRecursiveFlag(it) }

        // --- REFUSE: user data and system roots ---------------------------
        for (p in paths) {
            val n = normalize(p)
            if (n in refusedExact) {
                return Decision(Verdict.REFUSE,
                    "«$n» — системный каталог", n)
            }
            for (prefix in refusedAbsolute) {
                if (n == prefix || n.startsWith("$prefix/")) {
                    return Decision(Verdict.REFUSE,
                        "«$n» — память, навыки или внешняя папка пользователя: " +
                            "копии нет, потеря необратима", n)
                }
            }
            for (prefix in refusedSoft) {
                if (n == prefix || n.startsWith("$prefix/")) {
                    val singleFile = !recursive && paths.size == 1 &&
                        !n.endsWith("/") && n != prefix
                    if (singleFile) continue
                    return Decision(Verdict.REFUSE,
                        "«$n» — проекты пользователя; можно удалить только " +
                            "один свой файл, не каталог и не пачку", n)
                }
            }
        }

        // --- CONFIRM: shapes that caused, or can cause, real damage -------
        val globs = paths.filter { hasGlob(it) }
        if (recursive && globs.isNotEmpty()) {
            return Decision(Verdict.CONFIRM,
                "рекурсивное удаление по маске — оболочка раскроет её до запуска, " +
                    "и под удаление попадёт больше, чем видно в команде",
                globs.joinToString(" "))
        }
        if (recursive && paths.size > 1) {
            return Decision(Verdict.CONFIRM,
                "рекурсивное удаление ${paths.size} путей одной командой",
                paths.joinToString(" "))
        }
        if (recursive && paths.size == 1) {
            val n = normalize(paths[0])
            if (looksLikeRepoOrWorktree(n)) {
                return Decision(Verdict.CONFIRM,
                    "«$n» похож на рабочий каталог репозитория — внутри может быть " +
                        "незакоммиченная работа",
                    n)
            }
            // Deleting a single scratch directory is normal work. Allow it.
            return Decision(Verdict.ALLOW)
        }
        if (globs.isNotEmpty() && paths.size == 1 && !recursive) {
            // `rm *.log` — mass file removal without recursion. Cheap to confirm,
            // and the same glob-surprise applies.
            return Decision(Verdict.CONFIRM,
                "удаление по маске: раскроется в неизвестное заранее число файлов",
                globs.joinToString(" "))
        }
        // `shred`/`unlink`/`truncate` on several targets at once: not recursive,
        // but shred is unrecoverable by design and truncate silently empties a
        // file. Worth one glance when more than one target is named.
        if (tool != "rm" && tool != "rmdir" && paths.size > 1) {
            return Decision(Verdict.CONFIRM,
                "$tool по ${paths.size} путям сразу — данные не восстановить",
                paths.joinToString(" "))
        }
        return Decision(Verdict.ALLOW)
    }

    /** Known parallel-session working clones plus the generic shapes. Names,
     *  not contents: the directory may be momentarily empty and still be in use. */
    private val knownClones = setOf(
        "/tmp/om", "/tmp/om2", "/tmp/om3", "/tmp/omw", "/tmp/omx",
        "/tmp/ds", "/tmp/ds2", "/tmp/ds3", "/tmp/ds4",
        "/tmp/mainui", "/tmp/fc", "/tmp/cs16c",
    )

    private fun looksLikeRepoOrWorktree(path: String): Boolean {
        if (path in knownClones) return true
        val name = path.substringAfterLast('/')
        // A path ending in .git, or a bare `.git`, is a repository by definition.
        return name == ".git" || path.endsWith("/.git")
    }

    /** Strip quotes and a trailing slash; make relative paths recognisable
     *  without resolving them (we have no cwd here). */
    private fun normalize(raw: String): String {
        var s = raw.trim().trim('"', '\'')
        if (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /** Whitespace tokenizer that keeps quoted spans together. */
    private fun tokenize(segment: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (c in segment) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else sb.append(c)
                }
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
