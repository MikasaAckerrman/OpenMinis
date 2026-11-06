package com.openminis.app.offload

import com.openminis.app.util.SafeDir

import android.content.Context
import java.io.File

/**
 * [T-agent-file] Scans the user's custom subagent directory and hands parsed
 * [AgentFileParser.AgentFile]s to the spawner.
 *
 * Layout mirrors skills: host `filesDir/minis-global/agents/<name>.md`,
 * which the sandbox exposes as `/var/minis/agents/<name>.md` — so the user
 * (or the agent itself via file_write) can add an agent with one file, no
 * code, no APK.
 *
 * No cache: discovery (list_agents) and spawn are rare events, and a stale
 * cache after an out-of-band file drop is exactly the class of bug the
 * skills loader already had to fix with reloadFromDisk.
 */
object AgentFileStore {

    private fun agentsDir(context: Context): File =
        File(context.applicationContext.filesDir, "minis-global/agents")

    /** Every valid agent file found; an empty list is a normal state. */
    fun list(context: Context): List<AgentFileParser.AgentFile> {
        val dir = agentsDir(context)
        val files = SafeDir.listFiles(dir) { f -> f.isFile && f.extension == "md" }
            ?: return emptyList()
        return files
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    AgentFileParser.parse(file.name, file.readText())
                } catch (_: Exception) {
                    null
                }
            }
    }

    /** Look up one agent by its spawn name (role="custom:<name>"). */
    fun find(context: Context, name: String): AgentFileParser.AgentFile? =
        list(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /**
     * [B16] The backing file for an agent-file name (Custom Agents screen
     * edit/delete). Null = no such file. The caller validates names before
     * constructing them; this only resolves inside the fixed agents dir.
     */
    fun fileFor(context: Context, fileName: String): File? {
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
            return null
        }
        val f = File(agentsDir(context), fileName)
        return if (f.isFile) f else null
    }

    /** Where the user drops agent files, for messages and docs. */
    const val SANDBOX_DIR = "/var/minis/agents"
}
