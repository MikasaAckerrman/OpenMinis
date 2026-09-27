package com.openminis.app.tools

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-parallel-write-contract] The write jail for agent worker sessions — the
 * ce-work conditional contract, tool-level enforcement, not prompt-level.
 *
 * WHY THIS EXISTS. Bind-mount topology (ExecutionCoordinator.buildSessionBindMounts):
 *  - `/var/minis/{workspace,attachments,offloads,browser}` are PER-SESSION —
 *    a worker physically cannot hit another worker's files there. No jail
 *    needed; isolation is already structural.
 *  - `/var/minis/{shared,memory,skills,mcp-servers}` are GLOBAL — every
 *    session (main chat AND all parallel workers) sees the same host dirs.
 *    Two workers writing there race; a worker doing `git add/commit` there
 *    contends on the shared index.lock (silent partial-stage corruption).
 *    This is the REAL parallel-write hazard in this architecture, and it is
 *    invisible at task-planning time ("independent tasks" can still both
 *    touch the global surface).
 *
 * THE CONTRACT (per the external review, 27.09): worker sessions may write
 * only their own session dirs + `/tmp` scratch. Writes to the global surface
 * are REFUSED with a redirect: the worker produces the artifact in its own
 * workspace and names it in the result; the SPAWNING orchestrator (unjailed,
 * the one that committed the baseline) integrates it after the batch. Reads
 * stay unrestricted everywhere — a jail that blocked reads would break
 * discovery roles.
 *
 * Null roots = unrestricted (the main chat and any legacy session).
 * Process-scoped map for process-scoped state — same reasoning as
 * [AgentToolPolicyStore]: no Room migration for data worthless after death.
 */
object AgentWritePolicyStore {

    /** Write roots granted to every jailed worker: own dirs + scratch. */
    val DEFAULT_JAIL_ROOTS: List<String> = listOf(
        "/var/minis/workspace",
        "/var/minis/attachments",
        "/var/minis/offloads",
        "/tmp",
    )

    /**
     * The global write surface — listed for messages and for the git-ban in
     * the shell path. These are read-friendly, write-hostile for workers.
     */
    val GLOBAL_SURFACE: List<String> = listOf(
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
    )

    private val jails = ConcurrentHashMap<String, List<String>>()

    /**
     * Jail [sessionId] to [roots]. Empty/null roots fall back to
     * [DEFAULT_JAIL_ROOTS] — "jail with no roots" would be a foot-gun (a
     * worker that can write NOWHERE cannot even produce its artifact).
     */
    fun setJail(sessionId: String, roots: List<String>? = null) {
        jails[sessionId] = (roots?.takeIf { it.isNotEmpty() } ?: DEFAULT_JAIL_ROOTS).toList()
    }

    fun clear(sessionId: String) {
        jails.remove(sessionId)
    }

    /** Write roots for [sessionId], or null when unrestricted. */
    fun rootsFor(sessionId: String): List<String>? = jails[sessionId]

    fun isJailed(sessionId: String): Boolean = jails.containsKey(sessionId)

    /**
     * May a jailed session write to [path] (sandbox-space path as the model
     * declared it)? Canonicalization is the load-bearing detail: without it
     * `/var/minis/workspace/../shared/x` trivially escapes the prefix check.
     * A path that does not exist yet canonicalizes through its deepest
     * existing parent — File.getCanonicalPath handles missing tails on
     * Android/Linux — and the roots themselves are canonicalized on the same
     * call so the comparison is honest on both sides.
     *
     * Symlink escape: getCanonicalPath resolves symlinks, so a symlink
     * planted inside the workspace pointing at the global surface is
     * followed and refused. (A pre-existing symlink the worker cannot
     * overwrite — the jail blocks creating it in the first place — is the
     * one residual gap, shared with every prefix-jail design; noted here so
     * it is a known limit, not a surprise.)
     */
    fun mayWriteTo(sessionId: String, path: String): Boolean {
        val roots = jails[sessionId] ?: return true
        if (path.isBlank()) return false
        return runCatching {
            val target = File(path).canonicalFile
            roots.any { root ->
                val rootFile = File(root).canonicalFile
                // Equality: writing the root dir itself (mkdir semantics).
                target == rootFile || target.startsWith(rootFile)
            }
        }.getOrDefault(false)
    }

    /**
     * Index-mutating git subcommands — the worker ban list. Word-scan over
     * the tokenized command (split on whitespace and the shell separators
     * `;|&()` so `cd x && git add .` is caught), matching a git invocation
     * whose first non-flag git argument is a mutation. Read-only verbs
     * (status, log, diff, show, blame, rev-parse, ls-files, grep, describe)
     * never match. Deliberately over-broad on ambiguous verbs (checkout,
     * reset, rm, branch, remote): they mutate the index/working tree, and a
     * worker's correct move is to hand files to the spawner anyway — a false
     * REFUSE costs one turn, a false ALLOW costs a corrupted index.
     */
    private val GIT_MUTATIONS = setOf(
        "add", "commit", "checkout", "switch", "restore", "stash", "push", "pull",
        "merge", "rebase", "reset", "rm", "mv", "clean", "worktree", "apply",
        "am", "cherry-pick", "revert", "bisect", "tag", "branch", "remote",
        "init", "clone", "submodule", "sparse-checkout", "update-index",
    )

    fun isGitMutation(command: String): Boolean {
        val tokens = command.split(Regex("[\\s;|&()<>]+"))
            .map { it.trim('\'', '"') }
            .filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == "git" || tokens[i].endsWith("/git")) {
                // Skip git's own flags (-C path, --git-dir=..., --no-pager...)
                var j = i + 1
                while (j < tokens.size && (tokens[j].startsWith("-"))) j++
                if (j < tokens.size) {
                    val sub = tokens[j].removeSuffix("!")
                    if (sub in GIT_MUTATIONS) return true
                }
                i = j
            } else {
                i++
            }
        }
        return false
    }
}
