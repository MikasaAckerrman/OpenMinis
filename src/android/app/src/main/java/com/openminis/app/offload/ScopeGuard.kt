package com.openminis.app.offload

import com.openminis.app.data.model.AgentNode
import com.openminis.app.data.model.Handoff

/**
 * [T-agent-graph-scope] Detects a node stepping outside its role.
 *
 * Why a code guard and not just a sterner prompt: role isolation in a
 * prompt-only system rests on the model choosing to comply. Weaker models
 * routinely "help" by doing the next agent's work — an architect that starts
 * emitting code, a reviewer that rewrites the file it was asked to review.
 * The prompt is the instruction; this is the check that the instruction held.
 *
 * The guard is deliberately conservative. It fires on evidence the handoff
 * carries the WRONG KIND of artifact, not on a hunch about quality. A false
 * stop is worse than a missed one: it halts a run that was going fine.
 */
internal object ScopeGuard {

    /** Verdict for one handoff. */
    sealed class Verdict {
        object Ok : Verdict()

        /** Hard stop: the node produced something it does not own. */
        data class OutOfScope(val reason: String) : Verdict()

        /** Soft: worth a trace line, not worth killing the run. */
        data class Suspicious(val reason: String) : Verdict()
    }

    /**
     * Markers that a deliverable is source code rather than prose. Fenced
     * blocks with a language tag, or a filename with a code extension.
     */
    private val CODE_MARKERS = listOf(
        "```kotlin", "```java", "```swift", "```python", "```ts",
        "```typescript", "```js", "```javascript", "```go", "```rust",
        "```c", "```cpp", "```sql", "```sh", "```bash",
    )

    private val CODE_EXTENSIONS = listOf(
        ".kt", ".java", ".swift", ".py", ".ts", ".tsx", ".js", ".jsx",
        ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs", ".rb", ".php",
    )

    /**
     * Roles that must never ship source files they wrote themselves.
     *
     * SOLUTION_ARCHITECT is on this list on purpose: it legitimately writes a
     * design DOCUMENT (and therefore holds file_write), but shipping a `.kt`
     * is exactly the drift we are guarding against — an architect that starts
     * implementing removes the reason the Implementer exists. The distinction
     * is made below by file EXTENSION, not by tool access.
     */
    private val NON_WRITING_ROLES = setOf(
        com.openminis.app.data.model.AgentRole.ORCHESTRATOR,
        com.openminis.app.data.model.AgentRole.REQUIREMENTS_ANALYST,
        com.openminis.app.data.model.AgentRole.CODEBASE_DISCOVERY,
        com.openminis.app.data.model.AgentRole.SOLUTION_ARCHITECT,
        com.openminis.app.data.model.AgentRole.CODE_CORRECTNESS_REVIEWER,
        com.openminis.app.data.model.AgentRole.SECURITY_REVIEWER,
        com.openminis.app.data.model.AgentRole.PERFORMANCE_REVIEWER,
        com.openminis.app.data.model.AgentRole.DEPENDENCY_GUARDIAN,
        com.openminis.app.data.model.AgentRole.TEST_QUALITY_AUDITOR,
        com.openminis.app.data.model.AgentRole.FINAL_GATEKEEPER,
    )

    /**
     * Check a node's handoff against its declared scope.
     *
     * @param node the node that produced [handoff]
     * @param handoff parsed handoff
     * @param rawResponse full model output, for code-fence detection
     */
    fun check(node: AgentNode, handoff: Handoff, rawResponse: String): Verdict {
        // 1. FROM must match the node's own role. A node claiming to be someone
        //    else is the clearest possible identity drift.
        if (handoff.from != node.role) {
            return Verdict.OutOfScope(
                "handoff claims FROM=${handoff.from} but this node is ${node.role}"
            )
        }

        // 2. A non-writing role must not ship code. Reviewers quote snippets,
        //    so a bare fence is not enough — require a code fence AND a
        //    deliverable that names a source file.
        if (node.role in NON_WRITING_ROLES) {
            val hasCodeFence = CODE_MARKERS.any { rawResponse.contains(it, ignoreCase = true) }
            val shipsSourceFile = handoff.deliverables.any { d ->
                CODE_EXTENSIONS.any { ext -> d.contains(ext, ignoreCase = true) }
            }
            if (hasCodeFence && shipsSourceFile) {
                return Verdict.OutOfScope(
                    "${node.role} delivered source files (${handoff.deliverables.joinToString(", ")}) — " +
                        "this role reports findings, it does not write code"
                )
            }
            if (hasCodeFence) {
                return Verdict.Suspicious(
                    "${node.role} emitted a code block; acceptable only as a quoted example"
                )
            }
        }

        // 3. TO must be in the node's delegation allowlist, when declared.
        if (node.mayDelegateTo.isNotEmpty() && handoff.to !in node.mayDelegateTo) {
            return Verdict.OutOfScope(
                "handoff targets ${handoff.to}, which is not in this node's " +
                    "mayDelegateTo (${node.mayDelegateTo.joinToString(", ")})"
            )
        }

        // 4. A COMPLETE handoff must actually deliver something.
        if (handoff.status == com.openminis.app.data.model.HandoffStatus.COMPLETE &&
            handoff.deliverables.isEmpty()
        ) {
            return Verdict.OutOfScope("status COMPLETE with an empty DELIVERABLES list")
        }

        return Verdict.Ok
    }

    /**
     * The scope contract, rendered for the node's system prompt. Written in
     * first person because "I must never" reads as a self-binding commitment
     * and empirically drifts less than third-person rules.
     */
    fun scopeContract(node: AgentNode, replicaIndex: Int = 0): String = buildString {
        appendLine("=== SCOPE CONTRACT (non-negotiable) ===")
        appendLine("MY ROLE: ${node.role.name}")
        if (node.ownedArtifact.isNotBlank()) {
            appendLine("THE ONLY ARTIFACT I MAY PRODUCE: ${node.ownedArtifact}")
            appendLine("If my output is not that artifact, I have failed — not helped.")
        }
        if (node.role in NON_WRITING_ROLES) {
            appendLine(
                "I MUST NEVER write, patch, or ship SOURCE files (.kt, .java, .py, " +
                    ".ts, …). Not even a small fix, not even when the fix is obvious " +
                    "to me. Producing code is another agent's exclusive job; doing it " +
                    "myself destroys the independence the pipeline exists to provide. " +
                    "Design documents, specs and reports are a different matter — those " +
                    "are my output when my role says so."
            )
        }
        if (node.mayDelegateTo.isNotEmpty()) {
            appendLine("I MAY hand off ONLY to: ${node.mayDelegateTo.joinToString(", ") { it.name }}")
        }
        if (node.replicas > 1) {
            val shard = node.shardFor(replicaIndex)
            appendLine("I AM REPLICA ${replicaIndex + 1} OF ${node.replicas}.")
            if (shard.isNotBlank()) {
                appendLine("MY SHARD — I touch NOTHING outside it: $shard")
                appendLine(
                    "Other replicas own the remaining shards. If I work outside my " +
                        "shard we will both edit the same file and one of us loses work."
                )
            }
        }
        appendLine("=== END SCOPE CONTRACT ===")
    }
}
