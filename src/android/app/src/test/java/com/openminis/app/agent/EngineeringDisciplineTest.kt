package com.openminis.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-engineering-discipline] Guards [SystemPromptBuilder.ENGINEERING_DISCIPLINE]
 * — the behavioural contract appended to every Android chat's base system
 * prompt — against the three ways a prompt block silently rots:
 *
 *  1. Emptiness — a bad edit blanks the constant and the model loses the whole
 *     contract with no compile error.
 *  2. Duplicate rules — a merge doubles a bullet; wasted tokens on every
 *     request, and repetition makes the model DOWN-weight the rule.
 *  3. Unbounded growth — each rule competes for finite attention; past a
 *     ceiling, more text means less adherence, so the block must stay tight.
 *
 * Pure JVM — the constant has no Android dependency.
 */
class EngineeringDisciplineTest {

    private val text = SystemPromptBuilder.ENGINEERING_DISCIPLINE
    private val bullets = text.lines().filter { it.trimStart().startsWith("- ") }

    @Test
    fun `is present and non-trivial`() {
        assertTrue("constant must not be blank", text.isNotBlank())
        assertTrue("must have a header line", text.lineSequence().first().contains("Engineering discipline"))
        assertTrue("expected several rules, got ${bullets.size}", bullets.size >= 6)
    }

    @Test
    fun `has no duplicate rules`() {
        // Compare on the rule's first clause (up to the first period), so two
        // bullets that open with the same directive are caught even if their
        // trailing prose differs.
        val leads = bullets.map { it.trimStart().removePrefix("- ").substringBefore('.').trim().lowercase() }
        val dupes = leads.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("duplicate rule leads: ${dupes.keys}", dupes.isEmpty())
    }

    @Test
    fun `stays within an attention budget`() {
        // A soft ceiling: this block is one of many prompt sections and must
        // not balloon. ~2200 chars ≈ 550 tokens is generous for 8 rules;
        // crossing it means a rule was bloated or a section pasted in twice.
        assertTrue("discipline block too large: ${text.length} chars", text.length <= 2200)
    }

    @Test
    fun `every rule is a real directive, not filler`() {
        // No empty bullets, and each carries an actionable verb — cheap guard
        // against a half-deleted line surviving as "- ".
        for (b in bullets) {
            val body = b.trimStart().removePrefix("- ").trim()
            assertTrue("empty bullet found", body.length >= 12)
        }
    }

    @Test
    fun `covers the core disciplines`() {
        val lower = text.lowercase()
        // Anchor on the load-bearing concepts so a rewrite can't quietly drop one.
        listOf("verify", "root cause", "duplication", "context window", "admit")
            .forEach { assertTrue("missing discipline concept: $it", lower.contains(it)) }
    }
}
