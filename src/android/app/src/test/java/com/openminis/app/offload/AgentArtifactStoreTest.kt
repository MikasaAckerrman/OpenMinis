package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-agent-graph-artifact-paths] Pins the two properties that turned a working
 * graph run into a reported crash.
 *
 * A run of builtin_light produced a valid handoff from its orchestrator and was
 * then killed by `FileNotFoundException: /var/minis/workspace/PROBE-3/
 * planner_handoff.md (ENOENT)`. Two separate mistakes stacked: the path was a
 * PRoot-rootfs path used as a host path, and the failed write was allowed to
 * propagate out of a node that had already succeeded.
 *
 * The store only accepts an already-resolved host directory, so the first
 * mistake is now unrepresentable here; these cases lock the second (failure is
 * reported, never thrown) plus the containment rule for model-supplied names.
 */
class AgentArtifactStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a write lands in the run directory`() {
        val dir = tmp.newFolder("run-1")
        val store = AgentArtifactStore(dir)
        assertTrue(store.write("planner_handoff.md", "=== HANDOFF START ==="))
        assertEquals("=== HANDOFF START ===", File(dir, "planner_handoff.md").readText())
    }

    @Test
    fun `a missing directory is created rather than failing the write`() {
        // The runner mkdirs up front, but a run whose directory was wiped
        // mid-flight must not take the node down with it.
        val dir = File(tmp.root, "not-yet/deeper")
        assertTrue(AgentArtifactStore(dir).write("a.md", "x"))
        assertTrue(File(dir, "a.md").exists())
    }

    @Test
    fun `an unresolved directory reports instead of throwing`() {
        // null = PRoot never booted, so no host path exists. The run must
        // continue; only persistence is lost.
        val errors = mutableListOf<String>()
        val store = AgentArtifactStore(null, onError = { errors.add(it) })
        assertFalse(store.isEnabled)
        assertFalse(store.write("a.md", "x"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("a.md"))
    }

    @Test
    fun `a write failure is reported, never thrown`() {
        // A file where the directory should be: mkdirs and writeText both fail.
        // The old code let this escape into the node and fail a completed step.
        val notADir = tmp.newFile("i-am-a-file")
        val errors = mutableListOf<String>()
        val ok = AgentArtifactStore(notADir, onError = { errors.add(it) })
            .write("a.md", "x")
        assertFalse(ok)
        assertEquals(1, errors.size)
    }

    @Test
    fun `a deliverable path cannot escape the run directory`() {
        // Deliverable names come from model output. Traversal must be reduced to
        // a plain file name inside the run's own directory.
        val dir = tmp.newFolder("run-2")
        val store = AgentArtifactStore(dir)
        assertTrue(store.write("../../escaped.md", "x"))
        assertTrue(File(dir, "escaped.md").exists())
        assertFalse(File(tmp.root, "escaped.md").exists())
    }

    @Test
    fun `an absolute deliverable path is reduced to its file name`() {
        val dir = tmp.newFolder("run-3")
        assertTrue(AgentArtifactStore(dir).write("/etc/passwd", "x"))
        assertTrue(File(dir, "passwd").exists())
    }

    @Test
    fun `an unusable name is refused with a reason`() {
        val dir = tmp.newFolder("run-4")
        val errors = mutableListOf<String>()
        val store = AgentArtifactStore(dir, onError = { errors.add(it) })
        assertFalse(store.write("..", "x"))
        assertFalse(store.write("", "x"))
        assertEquals(2, errors.size)
    }

    @Test
    fun `the index is written as readable json under a stable name`() {
        val dir = tmp.newFolder("run-5")
        val store = AgentArtifactStore(dir)
        assertTrue(store.writeIndex(mapOf("plan.md" to "the plan")))
        val text = File(dir, AgentArtifactStore.INDEX_FILE_NAME).readText()
        assertTrue(text.contains("plan.md"))
        assertTrue(text.contains("the plan"))
    }

    @Test
    fun `an empty index still produces a file so the run leaves a trace`() {
        val dir = tmp.newFolder("run-6")
        assertTrue(AgentArtifactStore(dir).writeIndex(emptyMap()))
        assertTrue(File(dir, AgentArtifactStore.INDEX_FILE_NAME).exists())
    }
}
