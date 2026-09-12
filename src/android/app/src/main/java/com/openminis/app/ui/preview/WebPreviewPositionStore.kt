package com.openminis.app.ui.preview

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Persists the web preview's last browsing position so it survives the
 * preview closing (user returns to the chat) and the app process dying.
 *
 * Two consumers, one file per concern:
 *  1. The user re-opens a link on the same host → the preview resumes from
 *     the saved URL instead of the link's target ("see where I stopped").
 *  2. The agent (PRoot sandbox) reads `/var/minis/workspace/preview_position.json`
 *     from the ACTIVE chat session's workspace — that's exactly the place the
 *     user left off, visible without asking them.
 *
 * In-app state lives in `filesDir/web_preview_position.json` (app-private);
 * the agent mirror is written through [com.openminis.app.sandbox.PRootKernel]
 * resolveSessionHostPath so it lands in the per-session workspace mount.
 * Mirror failures never break the in-app path (best-effort, logged).
 */
class WebPreviewPositionStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebPreviewPosition"
        private const val FILENAME = "web_preview_position.json"
        private const val MIRROR_LINUX_PATH = "/var/minis/workspace/preview_position.json"

        @Volatile
        private var instance: WebPreviewPositionStore? = null

        fun getInstance(context: Context): WebPreviewPositionStore {
            return instance ?: synchronized(this) {
                instance ?: WebPreviewPositionStore(context.applicationContext).also { instance = it }
            }
        }

        /** host("https://www.avito.ru/profile/messenger") → "www.avito.ru" */
        fun hostOf(url: String): String = try {
            URI(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** host → last URL on that host. */
    private val positions = mutableMapOf<String, String>()

    init {
        load()
    }

    fun lastFor(url: String): String? {
        val host = hostOf(url)
        if (host.isEmpty()) return null
        return positions[host]
    }

    @Synchronized
    fun record(url: String) {
        val host = hostOf(url)
        // Only http(s) pages resume — file:// and minis:// previews are
        // one-shot documents (regenerated sandbox outputs), not sessions.
        if (host.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (positions[host] == url) return
        positions[host] = url
        save()
    }

    private fun save() {
        try {
            val obj = JSONObject()
            for ((host, url) in positions) obj.put(host, url)
            File(context.filesDir, FILENAME).writeText(obj.toString())
            mirrorToActiveSession(obj)
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /**
     * Mirror the position map into the foregrounded chat's workspace so the
     * agent can read it at /var/minis/workspace/preview_position.json.
     * No active session (app closed to background, settings screen) → skip.
     */
    private fun mirrorToActiveSession(obj: JSONObject) {
        try {
            val sessionId = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId
                ?: return
            val hostFile = com.openminis.app.sandbox.PRootKernel
                .resolveSessionHostPath(sessionId, MIRROR_LINUX_PATH, context)
                ?: return
            hostFile.parentFile?.mkdirs()
            hostFile.writeText(obj.toString())
        } catch (e: Exception) {
            Log.w(TAG, "mirror failed: ${e.message}")
        }
    }

    private fun load() {
        try {
            val file = File(context.filesDir, FILENAME)
            if (!file.exists()) return
            val obj = JSONObject(file.readText())
            for (key in obj.keys()) positions[key] = obj.optString(key, "")
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }
}
