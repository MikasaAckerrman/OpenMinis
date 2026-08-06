package com.openminis.app.offload

import java.io.File

/**
 * [T-agent-graph-artifact-paths] Best-effort artifact persistence for a graph run.
 *
 * Two things went wrong here and both are worth encoding in one place.
 *
 * First, paths. `/var/minis/workspace/...` is a path inside the PRoot rootfs,
 * not a host path. AgentGraphRunner passed it straight to `File(...)`, which
 * resolves against the app's real filesystem root where no such directory
 * exists, so every artifact write threw ENOENT. Translation now happens once,
 * in the runner, and this store only ever sees an already-resolved host dir —
 * it cannot reintroduce the bug because it has no Linux path to mistranslate.
 *
 * Second, severity. The write happened immediately after a handoff had passed
 * validation, and the exception propagated out of the node, so a node that had
 * genuinely done its job was reported as having thrown. Persistence is a
 * convenience: losing the copy must not invalidate the result. Everything here
 * swallows failure and reports it through [onError] instead.
 */
internal class AgentArtifactStore(
    /** Resolved host directory for this run, or null when unresolvable. */
    private val hostDir: File?,
    /** Called with a human-readable reason when a write is skipped or fails. */
    private val onError: (String) -> Unit = {},
) {

    /** True when writes have somewhere to go. */
    val isEnabled: Boolean get() = hostDir != null

    /**
     * Write [content] to `<hostDir>/<fileName>`. Returns true when it landed.
     *
     * [fileName] is reduced to its last path segment: deliverable paths come
     * from model output, and a value like `../../etc/passwd` or an absolute
     * path must not let a run write outside its own artifact directory.
     */
    fun write(fileName: String, content: String): Boolean {
        val dir = hostDir ?: run {
            onError("no artifact directory resolved; skipped '$fileName'")
            return false
        }
        val safeName = File(fileName).name
        if (safeName.isEmpty() || safeName == "." || safeName == "..") {
            onError("refusing to write artifact with unusable name '$fileName'")
            return false
        }
        return try {
            dir.mkdirs()
            File(dir, safeName).writeText(content)
            true
        } catch (e: Exception) {
            onError("could not persist artifact '$safeName': ${e.message}")
            false
        }
    }

    /** Write the run's artifact index. Same best-effort contract as [write]. */
    fun writeIndex(artifacts: Map<String, String>): Boolean {
        val obj = org.json.JSONObject()
        for ((k, v) in artifacts) obj.put(k, v)
        return write(INDEX_FILE_NAME, obj.toString(2))
    }

    companion object {
        const val INDEX_FILE_NAME = "ARTIFACT_INDEX.json"
    }
}
