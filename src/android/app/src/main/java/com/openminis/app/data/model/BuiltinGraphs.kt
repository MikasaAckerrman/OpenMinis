package com.openminis.app.data.model

import java.util.UUID

/**
 * [T-builtin-graphs] Ready-to-use agent graph presets, shipped in the app.
 *
 * The graph machinery (AgentGraphRunner + AgentDispatcher auto-routing) is
 * complete, but a user who wants to try it has to hand-write a JSON graph
 * through minis-config — a high bar for a feature whose whole point is
 * "throw a task at a team of agents". These presets give every install a
 * working parallel pipeline out of the box:
 *
 *  - RESEARCH: split a question into parallel sub-searches, then merge.
 *    The cheapest demo of the fan-out/fan-in shape — two independent
 *    analysts racing, one synthesizer.
 *
 *  - CODE_REVIEW: sequential implement → parallel review (correctness +
 *    security simultaneously) → gate. The classic "implement-then-review"
 *    L2 pipeline.
 *
 *  - DEEP_DIVE: full L4 pipeline — requirements → discovery → architecture
 *    → implementation → parallel review (3 specialists) → final gate.
 *    The "room of tools" the user described: a task is decomposed, and
 *    specialist agents work in parallel.
 *
 * Node ids are STABLE (not random) so edges reference them by name.
 * modelRole is used instead of modelEntryId — the runner resolves
 * "coder" / "reviewer" / "planner" against the user's configured
 * per-role model bindings, falling back to the session model.
 */
object BuiltinGraphs {

    /** Fixed node ids — edges reference these. */
    private const val REQ = "requirements"
    private const val DISCOVERY = "discovery"
    private const val ARCHITECTURE = "architecture"
    private const val IMPLEMENT = "implement"
    private const val REVIEW_CORRECT = "review-correctness"
    private const val REVIEW_SECURITY = "review-security"
    private const val REVIEW_PERF = "review-performance"
    private const val GATE = "final-gate"
    private const val SYNTH = "synthesizer"

    // ── RESEARCH: 2 parallel + 1 merge ─────────────────────────────────────

    val RESEARCH: AgentGraph = AgentGraph(
        id = "builtin-research",
        name = "Parallel Research",
        version = 1,
        nodes = listOf(
            AgentNode(
                id = "researcher-a",
                role = AgentRole.CODEBASE_DISCOVERY,
                systemPrompt = "You are Researcher A. Investigate the task from the FIRST angle you find most productive. Be concise, factual, cite evidence. Your output feeds a synthesizer.",
                ownedArtifact = "research findings (angle A)",
                modelRole = "analyst",
                maxTurns = 6,
            ),
            AgentNode(
                id = "researcher-b",
                role = AgentRole.CODEBASE_DISCOVERY,
                systemPrompt = "You are Researcher B. Investigate the task from a DIFFERENT angle than Researcher A (assume they took the obvious path — you take the contrarian or deeper path). Be concise, factual, cite evidence.",
                ownedArtifact = "research findings (angle B)",
                modelRole = "analyst",
                maxTurns = 6,
            ),
            AgentNode(
                id = SYNTH,
                role = AgentRole.ORCHESTRATOR,
                systemPrompt = "Synthesize the two research reports into a single coherent answer. Highlight agreements, resolve contradictions, note open questions. This is the final output the user sees.",
                ownedArtifact = "synthesized research summary",
                modelRole = "planner",
                maxTurns = 3,
            ),
        ),
        edges = listOf(
            AgentEdge(from = "researcher-a", to = SYNTH, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = "researcher-b", to = SYNTH, type = EdgeType.SEQUENTIAL),
        ),
        entryNodeId = "researcher-a", // runner dispatches ALL root nodes in parallel
        exitNodeIds = listOf(SYNTH),
        config = GraphConfig(maxParallelNodes = 2),
    )

    // ── CODE_REVIEW: implement → 2 parallel reviewers → gate ──────────────

    val CODE_REVIEW: AgentGraph = AgentGraph(
        id = "builtin-code-review",
        name = "Implement + Parallel Review",
        version = 1,
        nodes = listOf(
            AgentNode(
                id = IMPLEMENT,
                role = AgentRole.SENIOR_IMPLEMENTER,
                systemPrompt = "Implement the requested change. Write clean, minimal, working code. Your diff will be reviewed by TWO parallel specialists (correctness + security) — they will catch mistakes, so focus on the solution, not on defending it.",
                ownedArtifact = "production code diff",
                modelRole = "coder",
                maxTurns = 15,
            ),
            AgentNode(
                id = REVIEW_CORRECT,
                role = AgentRole.CODE_CORRECTNESS_REVIEWER,
                systemPrompt = "Review the implementation for CORRECTNESS: logic errors, edge cases, null safety, resource leaks. List specific issues with file:line references. Approve only if you'd merge this.",
                ownedArtifact = "correctness review findings",
                modelRole = "reviewer",
                maxTurns = 6,
            ),
            AgentNode(
                id = REVIEW_SECURITY,
                role = AgentRole.SECURITY_REVIEWER,
                systemPrompt = "Review the implementation for SECURITY: injection, path traversal, unsafe deserialization, credential leaks, permission escalation. List specific issues with file:line references.",
                ownedArtifact = "security review findings",
                modelRole = "reviewer",
                maxTurns = 6,
            ),
            AgentNode(
                id = GATE,
                role = AgentRole.FINAL_GATEKEEPER,
                systemPrompt = "You are the final gate. If BOTH reviewers approved, approve. If either found blocking issues, reject with a one-line summary of what must change. Be decisive — no 'needs more investigation'.",
                ownedArtifact = "gate decision",
                modelRole = "reviewer",
                maxTurns = 3,
            ),
        ),
        edges = listOf(
            AgentEdge(from = IMPLEMENT, to = REVIEW_CORRECT, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = IMPLEMENT, to = REVIEW_SECURITY, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = REVIEW_CORRECT, to = GATE, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = REVIEW_SECURITY, to = GATE, type = EdgeType.SEQUENTIAL),
        ),
        entryNodeId = IMPLEMENT,
        exitNodeIds = listOf(GATE),
        config = GraphConfig(maxParallelNodes = 2),
    )

    // ── DEEP_DIVE: full L4 pipeline ────────────────────────────────────────

    val DEEP_DIVE: AgentGraph = AgentGraph(
        id = "builtin-deep-dive",
        name = "Deep Dive (full pipeline)",
        version = 1,
        nodes = listOf(
            AgentNode(
                id = REQ,
                role = AgentRole.REQUIREMENTS_ANALYST,
                systemPrompt = "Extract the ACTUAL requirement from the user's request. What problem are they solving? What are the constraints? What does 'done' look like? One page max.",
                ownedArtifact = "requirements document",
                modelRole = "analyst",
                maxTurns = 4,
            ),
            AgentNode(
                id = DISCOVERY,
                role = AgentRole.CODEBASE_DISCOVERY,
                systemPrompt = "Explore the codebase. Find the files, functions, and patterns relevant to the requirement. Map what exists before designing what should change.",
                ownedArtifact = "codebase map",
                modelRole = "analyst",
                maxTurns = 10,
            ),
            AgentNode(
                id = ARCHITECTURE,
                role = AgentRole.SOLUTION_ARCHITECT,
                systemPrompt = "Design the solution. Given the requirements and the codebase map, produce a plan: what files to change, in what order, with what interfaces. Account for the reviewers' concerns proactively.",
                ownedArtifact = "architecture design document",
                modelRole = "architect",
                maxTurns = 6,
            ),
            AgentNode(
                id = IMPLEMENT,
                role = AgentRole.SENIOR_IMPLEMENTER,
                systemPrompt = "Implement the architecture plan. Follow the design; deviate only when reality disagrees, and note the deviation. Write clean, working code.",
                ownedArtifact = "production code diff",
                modelRole = "coder",
                maxTurns = 20,
            ),
            AgentNode(
                id = REVIEW_CORRECT,
                role = AgentRole.CODE_CORRECTNESS_REVIEWER,
                systemPrompt = "Review the implementation for correctness. Logic, edge cases, error handling. Specific file:line references.",
                ownedArtifact = "correctness review",
                modelRole = "reviewer",
                maxTurns = 6,
            ),
            AgentNode(
                id = REVIEW_SECURITY,
                role = AgentRole.SECURITY_REVIEWER,
                systemPrompt = "Review the implementation for security. Injection, traversal, leaks. Specific file:line references.",
                ownedArtifact = "security review",
                modelRole = "reviewer",
                maxTurns = 6,
            ),
            AgentNode(
                id = REVIEW_PERF,
                role = AgentRole.PERFORMANCE_REVIEWER,
                systemPrompt = "Review the implementation for performance. Allocations, loops, layout passes, I/O. Only flag things that measurably matter.",
                ownedArtifact = "performance review",
                modelRole = "reviewer",
                maxTurns = 6,
            ),
            AgentNode(
                id = GATE,
                role = AgentRole.FINAL_GATEKEEPER,
                systemPrompt = "Final gate. Weigh all three reviews. Approve if the implementation is sound; reject with specific actionable feedback if not.",
                ownedArtifact = "final gate decision",
                modelRole = "reviewer",
                maxTurns = 3,
            ),
        ),
        edges = listOf(
            AgentEdge(from = REQ, to = DISCOVERY, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = DISCOVERY, to = ARCHITECTURE, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = ARCHITECTURE, to = IMPLEMENT, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = IMPLEMENT, to = REVIEW_CORRECT, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = IMPLEMENT, to = REVIEW_SECURITY, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = IMPLEMENT, to = REVIEW_PERF, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = REVIEW_CORRECT, to = GATE, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = REVIEW_SECURITY, to = GATE, type = EdgeType.SEQUENTIAL),
            AgentEdge(from = REVIEW_PERF, to = GATE, type = EdgeType.SEQUENTIAL),
        ),
        entryNodeId = REQ,
        exitNodeIds = listOf(GATE),
        config = GraphConfig(maxParallelNodes = 3),
    )

    val ALL: List<AgentGraph> = listOf(RESEARCH, CODE_REVIEW, DEEP_DIVE)

    fun byId(id: String): AgentGraph? = ALL.firstOrNull { it.id == id }

    /**
     * Install the presets into the repository if the user has no graphs yet.
     * Called from MinisApp init. Idempotent: existing graphs (including
     * previously installed presets with user modifications) are left alone —
     * the ids are stable, so a re-install only fills genuinely missing slots.
     */
    fun installIfMissing(
        listExisting: () -> List<String>,
        save: (AgentGraph) -> Unit,
    ) {
        val existing = listExisting().toSet()
        for (preset in ALL) {
            if (preset.id !in existing) {
                save(preset)
            }
        }
    }
}
