package com.openminis.app.data

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Monitors guard_pending.json for deletion approval requests from minis-guard.
 *
 * Flow:
 * 1. Agent runs rm/mv in sandbox
 * 2. minis-guard writes guard_pending.json with command details
 * 3. DeletionGuardMonitor detects the file (FileObserver)
 * 4. ChatScreen shows a popup: "Agent wants to delete: [path]. Allow?"
 * 5. User taps Allow/Deny
 * 6. Response written to guard_response.json
 * 7. minis-guard reads response and proceeds/blocks
 *
 * Files are in filesDir/minis-global/workspace/ which maps to
 * /var/minis/workspace/ in the PRoot sandbox.
 */
class DeletionGuardMonitor(private val context: Context) {

    companion object {
        private const val TAG = "DeletionGuardMonitor"
        // filesDir/minis-global/workspace/ → /var/minis/workspace/ in sandbox
        private val PENDING_FILE get() = "guard_pending.json"
        private val RESPONSE_FILE get() = "guard_response.json"
    }

    data class GuardRequest(
        val command: String,
        val args: List<String>,
        val timestamp: Long,
    ) {
        val displayPath: String
            get() = args.firstOrNull { it.startsWith("/") || it.startsWith("-") == false }
                ?.let { if (it.startsWith("-")) args.firstOrNull { !it.startsWith("-") } ?: it else it }
                ?: args.joinToString(" ")
    }

    private val _pendingRequest = MutableStateFlow<GuardRequest?>(null)
    val pendingRequest: StateFlow<GuardRequest?> = _pendingRequest.asStateFlow()

    private var observer: FileObserver? = null

    private val watchDir: File by lazy {
        File(context.filesDir, "minis-global/workspace").also { it.mkdirs() }
    }

    fun start() {
        if (observer != null) return

        // FileObserver watches the directory for file creation/modification
        observer = object : FileObserver(watchDir.absolutePath, MODIFY or CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == PENDING_FILE) {
                    readPendingRequest()
                }
            }
        }
        observer?.startWatching()

        // Check for existing pending request on startup
        readPendingRequest()

        Log.i(TAG, "DeletionGuardMonitor started, watching ${watchDir.absolutePath}")
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    private fun readPendingRequest() {
        val pendingFile = File(watchDir, PENDING_FILE)
        if (!pendingFile.exists()) return

        try {
            val json = JSONObject(pendingFile.readText())
            val request = GuardRequest(
                command = json.optString("command", "unknown"),
                args = json.optJSONArray("args")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }
                } ?: emptyList(),
                timestamp = json.optLong("timestamp", 0),
            )
            _pendingRequest.value = request
            Log.i(TAG, "Pending deletion request: ${request.command} ${request.displayPath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read guard_pending.json: ${e.message}")
        }
    }

    /**
     * Write the user's approval/denial response.
     * Called from the popup dialog when user taps Allow or Deny.
     */
    fun respond(allow: Boolean) {
        val responseFile = File(watchDir, RESPONSE_FILE)
        val pendingFile = File(watchDir, PENDING_FILE)

        try {
            val response = JSONObject()
            response.put("allow", allow)
            response.put("timestamp", System.currentTimeMillis() / 1000)
            responseFile.writeText(response.toString())

            // Clear the pending request
            if (pendingFile.exists()) pendingFile.delete()
            _pendingRequest.value = null

            Log.i(TAG, "Guard response: allow=$allow")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write guard response: ${e.message}")
        }
    }

    /**
     * Dismiss the pending request without responding (e.g. dialog dismissed).
     * The guard will timeout and deny by default.
     */
    fun dismiss() {
        _pendingRequest.value = null
    }
}
