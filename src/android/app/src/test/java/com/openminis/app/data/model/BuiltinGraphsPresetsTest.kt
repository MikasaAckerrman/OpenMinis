package com.openminis.app.data.model

import com.openminis.app.offload.ConditionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the preset graphs ([BuiltinGraphs] in this package — the
 * user-facing family; the offload family has its own test). Cheap to check,
 * expensive to get wrong: a broken preset ships to every fresh install.
 */
class BuiltinGraphsPresetsTest {

    @Test
    fun `all presets pass structural validation`() {
        for (graph in BuiltinGraphs.ALL) {
            assertEquals(
                "preset '${graph.id}' must validate cleanly",
                emptyList<String>(),
                graph.validate(),
            )
        }
    }

    @Test
    fun `all preset ids are unique and stable`() {
        val ids = BuiltinGraphs.ALL.map { it.id }
        assertEquals("duplicate preset ids", ids.size, ids.toSet().size)
        // Stable ids: installIfMissing never overwrites, so an id change
        // would orphan the installed row and re-install a near-duplicate.
        assertTrue(ids.containsAll(listOf(
            "builtin-research",
            "builtin-code-review",
            "builtin-deep-dive",
            "builtin-ci-autofix",
        )))
    }

    @Test
    fun `conditional edge conditions parse`() {
        // An unparseable condition silently never matches: the edge becomes a
        // dead letter and the run quietly skips a whole branch. isSyntaxValid
        // exists exactly to reject this at save time — the presets must pass it.
        for (graph in BuiltinGraphs.ALL) {
            for (edge in graph.edges) {
                assertTrue(
                    "preset '${graph.id}' edge ${edge.from}->${edge.to} condition " +
                        "'${edge.condition}' must parse",
                    ConditionEvaluator.isSyntaxValid(edge.condition),
                )
            }
        }
    }

    @Test
    fun `ci autofix has deliberately empty exits`() {
        // REGRESSION GUARD for a subtle runner interaction: with the fixer
        // declared as an exit node, a green CI (conditional edge false) drains
        // the queue with the exit unsettled and the runner reports a false
        // DEADLOCK ("incomplete exit nodes"). Empty exitNodeIds selects the
        // run-to-exhaustion semantics where BOTH paths — green (watcher only)
        // and red (watcher → fixer) — terminate exactly when the queue drains.
        // If this test now fails, someone added an exit node: re-read the
        // runner's deadlock branch before "fixing" the test.
        val g = BuiltinGraphs.byId("builtin-ci-autofix")!!
        assertEquals(
            "ci-autofix must rely on drain-termination, not exit nodes",
            emptyList<String>(),
            g.exitNodeIds,
        )
    }

    @Test
    fun `ci autofix watcher cannot write or delegate`() {
        // The watcher is the report-what-IS role: give it write tools and it
        // will "helpfully" fix the failure itself, destroying the division the
        // graph exists to provide (same principle as the review graphs: the
        // observer must not be able to change what it observes).
        val watcher = BuiltinGraphs.byId("builtin-ci-autofix")!!.nodes
            .first { it.id == "ci-watcher" }
        assertEquals(
            "watcher tools must be shell-only (curl polling)",
            listOf("shell"),
            watcher.allowedTools,
        )
        assertTrue(
            "watcher must not be allowed to delegate",
            watcher.mayDelegateTo.isEmpty(),
        )
    }

    @Test
    fun `ci autofix fixer is a single implementer with a local check gate`() {
        val fixer = BuiltinGraphs.byId("builtin-ci-autofix")!!.nodes
            .first { it.id == "ci-fixer" }
        assertEquals(1, fixer.replicas)
        assertTrue(
            "fixer needs file tools to repair the failure",
            fixer.allowedTools.containsAll(listOf("file_read", "file_edit", "file_write")),
        )
        assertTrue(
            "fixer prompt must mandate the local compile check before push " +
                "(the point-of-no-return asymmetry)",
            fixer.systemPrompt.contains("kotlincheck.sh"),
        )
    }

    @Test
    fun `every preset node has a positive tool budget`() {
        // maxTurns doubles as the worker tool budget (AgentGraphRunner arms
        // AgentToolBudgetStore with it). A node without it wanders the whole
        // run — the run-9eb70345 lesson from the offload family.
        for (graph in BuiltinGraphs.ALL) {
            for (node in graph.nodes) {
                assertTrue(
                    "preset '${graph.id}' node '${node.id}' has maxTurns=${node.maxTurns}",
                    node.maxTurns > 0,
                )
            }
        }
    }
}
