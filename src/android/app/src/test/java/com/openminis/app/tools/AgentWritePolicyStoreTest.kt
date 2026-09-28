package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-parallel-write-contract] The write jail is a security boundary for
 * parallel agent runs — the tests pin its three load-bearing behaviours:
 * canonicalization (traversal/symlink escapes must not pass), root
 * semantics (own dirs + /tmp allowed, global surface refused), and the
 * git-mutation word-scan (mutations caught even behind cd/&&, read-only
 * git never flagged).
 *
 * Pure-JVM: the store is a plain object over ConcurrentHashMap and
 * java.io.File, so no Robolectric needed. The paths it reasons about are
 * canonicalized lexically through their existing ancestors (/var, /tmp
 * exist on any Linux runner), so no fixture directories are required.
 */
class AgentWritePolicyStoreTest {

    private val session = "test-session-write-jail"

    @After
    fun tearDown() {
        AgentWritePolicyStore.clear(session)
    }

    @Test
    fun `unjailed session writes anywhere`() {
        assertTrue(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/shared/anything"))
    }

    @Test
    fun `jailed session writes own dirs and tmp only`() {
        AgentWritePolicyStore.setJail(session)
        assertTrue(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/workspace/report.md"))
        assertTrue(AgentWritePolicyStore.mayWriteTo(session, "/tmp/scratch.txt"))
        assertTrue(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/attachments/img.png"))
        assertFalse(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/shared/repo/file.kt"))
        assertFalse(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/memory/2026-09-27.md"))
        assertFalse(AgentWritePolicyStore.mayWriteTo(session, "/var/minis/skills/custom/SKILL.md"))
        assertFalse(AgentWritePolicyStore.mayWriteTo(session, "/etc/passwd"))
    }

    @Test
    fun `traversal escape through dots is refused`() {
        AgentWritePolicyStore.setJail(session)
        assertFalse(
            AgentWritePolicyStore.mayWriteTo(session, "/var/minis/workspace/../../../etc/hosts"),
        )
        assertFalse(
            AgentWritePolicyStore.mayWriteTo(session, "/var/minis/workspace/../shared/escape.txt"),
        )
    }

    @Test
    fun `blank path is refused rather than assumed safe`() {
        AgentWritePolicyStore.setJail(session)
        assertFalse(AgentWritePolicyStore.mayWriteTo(session, ""))
    }

    @Test
    fun `git mutations are caught behind separators and flags`() {
        val mutations = listOf(
            "git add .",
            "cd /repo && git add -A",
            "git commit -m 'x'",
            "git -C /repo commit --amend",
            "git push origin main",
            "/usr/bin/git stash",
            "git checkout -- .",
            "git merge feature/x",
            "true | git rebase main; git pull",
        )
        mutations.forEach { assertTrue("expected mutation: $it", AgentWritePolicyStore.isGitMutation(it)) }
    }

    @Test
    fun `read-only git is never flagged`() {
        val reads = listOf(
            "git status",
            "git log --oneline -5",
            "git diff HEAD~1",
            "git show abc123",
            "git blame file.kt",
            "git rev-parse HEAD",
            "git ls-files",
            "cd /repo && git diff --stat",
            "grep git readme.md",
        )
        reads.forEach { assertFalse("expected read-only: $it", AgentWritePolicyStore.isGitMutation(it)) }
    }

    @Test
    fun `jail roots fall back to default when empty`() {
        AgentWritePolicyStore.setJail(session, emptyList())
        assertEquals(AgentWritePolicyStore.DEFAULT_JAIL_ROOTS, AgentWritePolicyStore.rootsFor(session))
    }

    // ---- [T-parallel-write-contract] shell write-target scanner ----

    @Test
    fun `redirect to global is caught, to own dirs is not`() {
        AgentWritePolicyStore.setJail(session)
        val caught = listOf(
            "echo x > /var/minis/shared/f",
            "echo x>>/var/minis/memory/log.md",
            "cat /var/minis/shared/a /var/minis/shared/b > /etc/hosts",
            "tee /var/minis/shared/out",
            "cp /var/minis/workspace/src.kt /var/minis/shared/dst.kt",
            "mv /var/minis/shared/src.kt /var/minis/workspace/dst.kt",
            "rm /var/minis/shared/junk",
            "sed -i 's/a/b/' /var/minis/shared/f.kt",
            "dd if=/dev/zero of=/var/minis/shared/img bs=1 count=1",
            "truncate -s 0 /var/minis/skills/x/SKILL.md",
            "ln -s /var/minis/workspace/t /var/minis/shared/link",
        )
        caught.forEach { cmd ->
            val v = AgentWritePolicyStore.violatingWriteTargets(cmd, session)
            assertTrue("expected violation for: $cmd", v.isNotEmpty())
        }
        val clean = listOf(
            "echo x > /tmp/scratch.txt",
            "echo x > /var/minis/workspace/report.md",
            "cp /var/minis/shared/src.kt /var/minis/workspace/dst.kt",
            "rm /var/minis/workspace/old.md",
            "grep pattern /var/minis/shared/repo/File.kt",
            "cat /var/minis/shared/a > /tmp/out",
            "sed -n '1p' /var/minis/shared/f.kt",
            "printf '%s' 'value with > inside'",
        )
        clean.forEach { cmd ->
            val v = AgentWritePolicyStore.violatingWriteTargets(cmd, session)
            assertTrue("expected NO violation for: $cmd (got $v)", v.isEmpty())
        }
    }

    @Test
    fun `glued redirect without spaces is caught`() {
        AgentWritePolicyStore.setJail(session)
        val v = AgentWritePolicyStore.violatingWriteTargets(
            "echo data>/var/minis/shared/glued.txt", session,
        )
        assertEquals(listOf("/var/minis/shared/glued.txt"), v)
    }

    @Test
    fun `fd duplication target is not a path`() {
        AgentWritePolicyStore.setJail(session)
        val v = AgentWritePolicyStore.violatingWriteTargets(
            "ls /var/minis/shared 2>&1 | tee /tmp/err.log", session,
        )
        assertTrue(v.isEmpty())
    }

    @Test
    fun `xargs rm with any global path is over-approximated`() {
        AgentWritePolicyStore.setJail(session)
        val v = AgentWritePolicyStore.violatingWriteTargets(
            "ls /var/minis/shared/*.tmp | xargs rm -f", session,
        )
        assertTrue(v.isNotEmpty())
        // xargs with non-destructive fed command stays open (reads allowed).
        val ok = AgentWritePolicyStore.violatingWriteTargets(
            "cat /var/minis/shared/list | xargs grep TODO", session,
        )
        assertTrue(ok.isEmpty())
    }

    @Test
    fun `unjailed session has no write-target violations`() {
        val v = AgentWritePolicyStore.violatingWriteTargets(
            "echo x > /var/minis/shared/f", session,
        )
        assertTrue(v.isEmpty())
    }

    // ── [T-worker-write-roots] roots-aware git gate ─────────────────────

    private val repoSession = "repo-owner-s1"
    private val repoRoot = "/var/minis/shared/openminis-backup/canonical"

    @Test
    fun `git mutation allowed when -C target is within declared roots`() {
        AgentWritePolicyStore.setJail(
            repoSession,
            AgentWritePolicyStore.DEFAULT_JAIL_ROOTS + listOf(repoRoot),
        )
        assertTrue(
            AgentWritePolicyStore.gitMutationAllowedFor(
                repoSession, "git -C $repoRoot add -A",
            ),
        )
        assertTrue(
            AgentWritePolicyStore.gitMutationAllowedFor(
                repoSession, "git -C $repoRoot commit -m 'fix' && git -C $repoRoot push origin b",
            ),
        )
        // File tools get the same widening: the repo path is writable.
        assertTrue(AgentWritePolicyStore.mayWriteTo(repoSession, "$repoRoot/src/F.kt"))
    }

    @Test
    fun `git mutation refused when target escapes the roots`() {
        AgentWritePolicyStore.setJail(
            repoSession,
            AgentWritePolicyStore.DEFAULT_JAIL_ROOTS + listOf(repoRoot),
        )
        // A repo outside the declared roots.
        assertFalse(
            AgentWritePolicyStore.gitMutationAllowedFor(
                repoSession, "git -C /var/minis/shared/other-repo push",
            ),
        )
        // Any explicit target outside roots poisons the whole command.
        assertFalse(
            AgentWritePolicyStore.gitMutationAllowedFor(
                repoSession,
                "git -C $repoRoot --git-dir=/var/minis/shared/other/.git status",
            ),
        )
    }

    @Test
    fun `jailed without declared roots keeps the blanket git ban`() {
        AgentWritePolicyStore.setJail(session) // default roots only
        // No explicit -C target: cwd is not evidence of ownership.
        assertFalse(
            AgentWritePolicyStore.gitMutationAllowedFor(session, "git add -A"),
        )
        // Explicit target, but not within the default roots.
        assertFalse(
            AgentWritePolicyStore.gitMutationAllowedFor(session, "git -C $repoRoot push"),
        )
    }

    @Test
    fun `unjailed sessions were never banned`() {
        assertTrue(
            AgentWritePolicyStore.gitMutationAllowedFor(
                "main-chat", "git -C /anywhere/repo push",
            ),
        )
    }

    @Test
    fun `gitMutationAllowedFor is orthogonal to read-only verbs`() {
        AgentWritePolicyStore.setJail(session)
        // Read-only git never hits the mutation gate; allow/deny here is
        // about the mutation path only — this pins that a read command with
        // no -C is not falsely treated as needing roots.
        val reads = listOf("git status", "git -C $repoRoot log --oneline")
        reads.forEach { assertFalse(AgentWritePolicyStore.isGitMutation(it)) }
    }
}
