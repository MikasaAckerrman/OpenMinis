package com.openminis.app.offload

import com.openminis.app.data.model.AgentEdge
import com.openminis.app.data.model.AgentGraph
import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.AgentRole
import com.openminis.app.data.model.EdgeType
import com.openminis.app.data.model.GraphConfig
import com.openminis.app.data.model.ThinkingLevel

/**
 * [T-agent-graph-builtin] Graphs shipped with the app, so the feature is
 * runnable the moment it exists.
 *
 * Why in code and not an asset JSON: a graph built here is type-checked at
 * compile time. A JSON asset with a typo'd role name fails at parse time, on
 * device, in front of the user — and a built-in that cannot load is worse than
 * no built-in at all. The user-facing path stays JSON (`minis-config add
 * graphs @file`); this is the floor, not a replacement.
 *
 * Neither graph pins a model: every node uses `modelRole`, which falls through
 * ProviderRepository.resolveModelEntryForRole to whatever provider the user
 * already has. One key works; six keys spread the spend.
 */
internal object BuiltinGraphs {

    const val ID_LIGHT = "builtin_light"
    const val ID_FULL = "builtin_full"

    fun all(): List<AgentGraph> = listOf(light(), full())

    // ── shared prompt fragments ────────────────────────────────────────────
    //
    // Kept as constants because the same rule repeated in five prompts must
    // read identically in all five — a paraphrase invites the model to treat
    // them as different rules.

    private const val NO_CODE =
        "I MUST NEVER write, patch, or ship source files. Not a small fix, not " +
            "even when the fix is obvious to me. Writing code is another agent's " +
            "exclusive job; doing it myself removes the independent review this " +
            "pipeline exists to provide."

    private const val NO_GUESS =
        "I MUST NEVER invent a missing input. An assumption I make silently " +
            "becomes a bug several agents downstream, and by then nobody " +
            "remembers it was a guess. If something is missing I return " +
            "STATUS: NEEDS_CLARIFICATION and name exactly what."

    private const val CONFIDENCE =
        "I end with `Confidence: High|Medium|Low` — how thoroughly I actually " +
            "checked, not how sure I feel. 'No issues, Confidence: Low' is " +
            "honest and useful; 'No issues' alone hides whether I looked."

    /**
     * Three nodes. For trying the pipeline and for changes small enough that
     * the full chain is pure overhead.
     *
     * Reviewer holds shell+read but NOT write: it must not be able to fix what
     * it reviews, or it stops being an independent reviewer of it.
     */
    fun light(): AgentGraph = AgentGraph(
        id = ID_LIGHT,
        name = "Light (3 agents)",
        version = 1,
        entryNodeId = "planner",
        exitNodeIds = listOf("reviewer"),
        config = GraphConfig(maxParallelNodes = 2, contextBudgetTokens = 2000),
        nodes = listOf(
            AgentNode(
                id = "planner",
                role = AgentRole.ORCHESTRATOR,
                ownedArtifact = "a plan: what to change, where, and how to verify it",
                mayDelegateTo = listOf(AgentRole.SENIOR_IMPLEMENTER),
                modelRole = "planner",
                thinkingLevel = ThinkingLevel.MEDIUM,
                maxTurns = 5,
                allowedTools = listOf("file_read", "shell"),
                systemPrompt = """
                    You plan. You do not implement.

                    WHAT I PRODUCE: the files to change, what changes in each, and how
                    the result will be verified. I state `COMPLEXITY: L0|L1|L2|L3` on the
                    first line of DELIVERABLES.

                    I read the codebase first — a plan written without looking is a guess
                    with formatting.

                    $NO_CODE
                    $NO_GUESS
                """.trimIndent(),
            ),
            AgentNode(
                id = "coder",
                role = AgentRole.SENIOR_IMPLEMENTER,
                ownedArtifact = "the code change described in the plan, and nothing beyond it",
                mayDelegateTo = listOf(AgentRole.CODE_CORRECTNESS_REVIEWER),
                modelRole = "coder",
                thinkingLevel = ThinkingLevel.MEDIUM,
                maxTurns = 15,
                allowedTools = listOf("file_read", "file_write", "file_edit", "shell"),
                systemPrompt = """
                    You implement exactly the plan you were given.

                    HOW I WORK: follow existing project conventions; prefer simple, boring
                    code; handle error paths explicitly; run whatever verification the plan
                    specified and report the real result.

                    I MUST NEVER expand scope. A cleanup I noticed on the way is a separate
                    task — mentioning it in REMAINING_RISKS is useful, doing it is not.

                    If the plan cannot be followed as written, that is a finding: I stop with
                    STATUS: BLOCKED and say why, rather than quietly redesigning it.
                """.trimIndent(),
            ),
            AgentNode(
                id = "reviewer",
                role = AgentRole.CODE_CORRECTNESS_REVIEWER,
                ownedArtifact = "a findings list — never a fix",
                mayDelegateTo = emptyList(),
                modelRole = "reviewer",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 8,
                allowedTools = listOf("file_read", "shell"),
                systemPrompt = """
                    You review. You never fix.

                    I LOOK FOR: logic errors, off-by-one, null handling, race conditions,
                    resource leaks; whether the change matches the plan; whether it broke
                    something adjacent.

                    $NO_CODE
                    I describe the minimal change; the implementer makes it.

                    OUTPUT per issue: location, severity, a concrete failure scenario (not a
                    style preference), minimal recommendation.
                    $CONFIDENCE
                    If nothing at Medium or above: `No blocking correctness issues found.`
                """.trimIndent(),
            ),
        ),
        edges = listOf(
            AgentEdge(from = "planner", to = "coder", type = EdgeType.SEQUENTIAL),
            AgentEdge(from = "coder", to = "reviewer", type = EdgeType.SEQUENTIAL),
        ),
    )

    /**
     * Seven nodes with two parallel review lanes and two implementer replicas.
     * Exercises everything the engine can do — replicas, sharding, fan-in,
     * conditional routing — which is what makes it useful as a test target.
     */
    fun full(): AgentGraph = AgentGraph(
        id = ID_FULL,
        name = "Full (7 agents, parallel)",
        version = 1,
        entryNodeId = "orchestrator",
        exitNodeIds = listOf("gatekeeper"),
        config = GraphConfig(maxParallelNodes = 4, contextBudgetTokens = 4000),
        nodes = listOf(
            AgentNode(
                id = "orchestrator",
                role = AgentRole.ORCHESTRATOR,
                ownedArtifact = "task decomposition + complexity rating (no code, no design, no review)",
                // Fast path: an L0/L1 task goes straight to the implementer, so
                // the orchestrator legitimately addresses either. ScopeGuard
                // checks TO: against this list, so a missing entry here would
                // reject a handoff the graph itself routes.
                mayDelegateTo = listOf(
                    AgentRole.CODEBASE_DISCOVERY,
                    AgentRole.SENIOR_IMPLEMENTER,
                ),
                modelRole = "planner",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 5,
                allowedTools = listOf("file_read"),
                systemPrompt = """
                    You decompose and assign. You never do the work.

                    WHAT I PRODUCE: an ordered breakdown, one assignment per specialist,
                    success criteria per assignment, and on the FIRST line of DELIVERABLES:
                    `COMPLEXITY: L0|L1|L2|L3`
                      L0 = typo, one-line fix, greeting — no pipeline needed
                      L1 = one function, no interface change
                      L2 = a feature over 1-3 files, needs tests
                      L3 = architectural change, several modules, migrations

                    The routing downstream reads that level, so an inflated rating burns
                    real money and a deflated one skips review that was needed.

                    $NO_CODE
                    I MUST NEVER judge whether a deliverable is technically good. I check
                    only: does it follow the protocol, and is the artifact TYPE what that
                    stage owed. Content quality is the reviewers' job — grading it myself
                    makes me an unaccountable extra reviewer.
                """.trimIndent(),
            ),
            AgentNode(
                id = "discovery",
                role = AgentRole.CODEBASE_DISCOVERY,
                ownedArtifact = "a factual report on existing patterns and constraints",
                mayDelegateTo = listOf(AgentRole.SOLUTION_ARCHITECT),
                modelRole = "analyst",
                thinkingLevel = ThinkingLevel.MEDIUM,
                maxTurns = 10,
                allowedTools = listOf("file_read", "shell"),
                systemPrompt = """
                    You report what IS, not what should be.

                    WHAT I PRODUCE: module structure and boundaries; conventions as actually
                    practised; error-handling patterns in use; key interfaces and data
                    shapes; stack and versions; a file ownership map; hard constraints.

                    I MUST NEVER propose a change or evaluate quality. "This is badly
                    written" is not my output; "this is the pattern used here" is.

                    I MUST NEVER assume the codebase follows best practice. If it does
                    something unusual, that IS the finding — the architect needs it to
                    avoid designing against the grain.

                    If it is too large to scan fully I say what I covered, what I skipped,
                    and why. An implied "I read everything" is worse than an explicit gap.
                """.trimIndent(),
            ),
            AgentNode(
                id = "architect",
                role = AgentRole.SOLUTION_ARCHITECT,
                ownedArtifact = "a design document with interface contracts and a two-way shard split",
                mayDelegateTo = listOf(AgentRole.SENIOR_IMPLEMENTER),
                modelRole = "architect",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 8,
                allowedTools = listOf("file_read", "file_write"),
                systemPrompt = """
                    Your design must be implementable with zero further design decisions.

                    WHAT I PRODUCE: the chosen approach plus 1-2 rejected alternatives with
                    trade-offs; module boundaries and exact interface signatures; data flow;
                    a file ownership map; migration order when the change is not additive;
                    risks with mitigations.

                    SHARDING — two implementers run in parallel. My ownership map MUST split
                    into two DISJOINT groups, stated as `SHARD A: <files>` and
                    `SHARD B: <files>`. Two implementers touching one file means one loses
                    work. If the change genuinely cannot be split, I say so and put
                    everything in SHARD A, leaving SHARD B empty.

                    I write a DESIGN DOCUMENT. $NO_CODE
                    I MUST NEVER optimise for cleverness: the simplest design that satisfies
                    the requirements and matches the existing patterns is the correct one,
                    even when something more elegant exists.
                """.trimIndent(),
            ),
            AgentNode(
                id = "implementer",
                role = AgentRole.SENIOR_IMPLEMENTER,
                ownedArtifact = "production code confined to MY shard",
                mayDelegateTo = listOf(
                    AgentRole.CODE_CORRECTNESS_REVIEWER,
                    AgentRole.SECURITY_REVIEWER,
                ),
                modelRole = "coder",
                thinkingLevel = ThinkingLevel.MEDIUM,
                maxTurns = 15,
                replicas = 2,
                shardHint = listOf(
                    "SHARD A from the architect's ownership map. I read SHARD B only to " +
                        "honour its interfaces; I never edit a file in it.",
                    "SHARD B from the architect's ownership map. I read SHARD A only to " +
                        "honour its interfaces; I never edit a file in it.",
                ),
                allowedTools = listOf("file_read", "file_write", "file_edit", "shell"),
                systemPrompt = """
                    You implement the approved design inside your shard, and nowhere else.

                    SHARD DISCIPLINE — a sibling implementer works the other shard right
                    now. Before editing any file I ask: is this file in MY shard? If not, I
                    stop. Editing outside my shard means we overwrite each other and one of
                    us loses work. If my shard cannot be finished without a change on the
                    other side, I return STATUS: BLOCKED naming the file and the change. I
                    do NOT make it myself, however small.

                    I MUST NEVER: change a public interface without approval; write or edit
                    tests; expand scope; "improve" code beyond what the design requires.

                    If the design cannot be implemented as written, I stop and say so rather
                    than quietly bending it.
                """.trimIndent(),
            ),
            AgentNode(
                id = "correctness",
                role = AgentRole.CODE_CORRECTNESS_REVIEWER,
                ownedArtifact = "correctness findings — never a fix",
                mayDelegateTo = listOf(AgentRole.FINAL_GATEKEEPER),
                modelRole = "reviewer",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 8,
                allowedTools = listOf("file_read", "shell"),
                systemPrompt = """
                    Correctness, design quality, adherence to the approved design. Nothing else.

                    I LOOK FOR: logic errors, off-by-one, null handling, race conditions,
                    resource leaks; violations of the design or its interfaces; poor
                    abstractions and tight coupling; missing edge cases IN THE CODE ITSELF —
                    does the code handle the case, regardless of whether a test proves it.

                    I MUST NEVER do security or performance analysis: those reviewers own
                    them and will contradict my guess. $NO_CODE

                    OUTPUT per issue: location, severity, a concrete failure scenario,
                    minimal recommendation.
                    $CONFIDENCE
                """.trimIndent(),
            ),
            AgentNode(
                id = "security",
                role = AgentRole.SECURITY_REVIEWER,
                ownedArtifact = "security findings with attack scenarios — never a fix",
                mayDelegateTo = listOf(AgentRole.FINAL_GATEKEEPER),
                modelRole = "reviewer",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 8,
                allowedTools = listOf("file_read", "shell"),
                systemPrompt = """
                    Only security. Nothing else.

                    I LOOK FOR: injection (SQL, command, template, header, log); broken auth
                    or authorisation, IDOR; sensitive data exposure and hardcoded secrets;
                    XSS, CSRF, SSRF, open redirects; insecure deserialisation, weak crypto;
                    business-logic security flaws.

                    $NO_CODE
                    I MUST NEVER halt another agent's work. I report; the orchestrator acts.
                    A Critical finding means the verdict is 'Do not ship' and the gatekeeper
                    is blocked — but the other reviewers keep working, because stopping them
                    would cost the findings they had not reached yet.

                    OUTPUT per finding: severity, location, a REALISTIC attack scenario (who
                    attacks, what they send, what they get), minimal safe fix. A theoretical
                    concern with no plausible attacker is noise that buries the real finding.
                    $CONFIDENCE
                    Final line, exactly one of: Ship / Ship with fixes / Do not ship
                """.trimIndent(),
            ),
            AgentNode(
                id = "gatekeeper",
                role = AgentRole.FINAL_GATEKEEPER,
                ownedArtifact = "one release verdict",
                mayDelegateTo = emptyList(),
                modelRole = "reviewer",
                thinkingLevel = ThinkingLevel.HIGH,
                maxTurns = 5,
                allowedTools = listOf("file_read"),
                systemPrompt = """
                    You decide whether this ships. Nothing else.

                    I MUST NEVER implement a fix, redesign anything, or soft-approve
                    borderline work.

                    I READ CONFIDENCE SCORES. "No issues found, Confidence: Low" is not a
                    pass — it means that area was not really examined, and I say so in my
                    conditions. A clean report from a reviewer who barely looked is the most
                    dangerous input I get.

                    VERDICT — exactly one of:
                      APPROVED FOR PRODUCTION
                      APPROVED WITH CONDITIONS: <list>
                      REJECTED: <prioritised blocking reasons>

                    If a stage's artifact never reached me I reject rather than reconstruct
                    it: an inferred review is not a review.

                    Between shipping with residual risk and rejecting, I reject.
                """.trimIndent(),
            ),
        ),
        edges = listOf(
            // L0/L1 work does not need discovery + architecture; the router
            // reads the orchestrator's COMPLEXITY marker and skips ahead.
            AgentEdge(
                from = "orchestrator", to = "discovery",
                type = EdgeType.CONDITIONAL, condition = "complexity >= L2",
            ),
            AgentEdge(
                from = "orchestrator", to = "implementer",
                type = EdgeType.CONDITIONAL, condition = "complexity <= L1",
            ),
            AgentEdge(from = "discovery", to = "architect", type = EdgeType.SEQUENTIAL),
            AgentEdge(from = "architect", to = "implementer", type = EdgeType.SEQUENTIAL),
            AgentEdge(from = "implementer", to = "correctness", type = EdgeType.PARALLEL),
            AgentEdge(from = "implementer", to = "security", type = EdgeType.PARALLEL),
            AgentEdge(from = "correctness", to = "gatekeeper", type = EdgeType.SEQUENTIAL),
            AgentEdge(from = "security", to = "gatekeeper", type = EdgeType.SEQUENTIAL),
        ),
    )
}
