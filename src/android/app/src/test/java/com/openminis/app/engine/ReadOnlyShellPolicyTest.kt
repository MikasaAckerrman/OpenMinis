package com.openminis.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyShellPolicyTest {

    private val policy = ReadOnlyShellPolicy

    @Test
    fun `read-only heads are allowed`() {
        assertFalse(policy.isMutatingCommand("ls -la"))
        assertFalse(policy.isMutatingCommand("cat /etc/hosts"))
        assertFalse(policy.isMutatingCommand("grep -r TODO /var/minis"))
        assertFalse(policy.isMutatingCommand("wc -l build.gradle.kts"))
        assertFalse(policy.isMutatingCommand("du -sh ."))
    }

    @Test
    fun `env-prefixed read commands classify by their head`() {
        assertFalse(policy.isMutatingCommand("LC_ALL=C sort /etc/passwd"))
        assertFalse(policy.isMutatingCommand("PAGER=cat man ls"))
    }

    @Test
    fun `git read subcommands are allowed`() {
        assertFalse(policy.isMutatingCommand("git status"))
        assertFalse(policy.isMutatingCommand("git log --oneline -5"))
        assertFalse(policy.isMutatingCommand("git diff HEAD~1"))
        assertFalse(policy.isMutatingCommand("git ls-files"))
    }

    @Test
    fun `git mutations are refused`() {
        assertTrue(policy.isMutatingCommand("git commit -m x"))
        assertTrue(policy.isMutatingCommand("git push"))
        assertTrue(policy.isMutatingCommand("git checkout -b x"))
    }

    @Test
    fun `redirection makes the command mutating`() {
        assertTrue(policy.isMutatingCommand("echo hi > /etc/hosts"))
        assertTrue(policy.isMutatingCommand("ls > /tmp/out"))
        assertTrue(policy.isMutatingCommand("cat a 2>/dev/null"))
    }

    @Test
    fun `pipes and chains are judged per segment`() {
        assertTrue(policy.isMutatingCommand("cat a | tee /etc/hosts"))
        assertTrue(policy.isMutatingCommand("ls; rm -rf /"))
        assertTrue(policy.isMutatingCommand("cat x && touch y"))
        assertFalse(policy.isMutatingCommand("cat a | grep b | wc -l"))
        assertFalse(policy.isMutatingCommand("ps aux || true"))
    }

    @Test
    fun `command substitution is refused outright`() {
        assertTrue(policy.isMutatingCommand("echo $(reboot)"))
        assertTrue(policy.isMutatingCommand("echo `reboot`"))
    }

    @Test
    fun `unknown heads are mutating - the safe default`() {
        assertTrue(policy.isMutatingCommand("rm -rf /"))
        assertTrue(policy.isMutatingCommand("python x.py"))
        assertTrue(policy.isMutatingCommand("npm install"))
        assertTrue(policy.isMutatingCommand("sh script.sh"))
        assertTrue(policy.isMutatingCommand(""))
    }
}
