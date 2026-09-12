package com.openminis.app.ui.preview

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.Executors

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
 *
 * T-position-io: all disk work runs on a single background thread — the
 * record() path fires from WebView callbacks (main thread) on every page
 * finish, and sync file writes there caused UI jank. All map mutation and
 * serialization is serialized onto that one thread; readers take the
 * monitor.
 */
class WebPreviewPositionStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebPreviewPosition"
        private const val FILENAME = "web_preview_position.json"
        private const val MIRROR_LINUX_PATH = "/var/minis/workspace/preview_position.json"
        private const val MAX_HOSTS = 32

        @Volatile
        private var instance: WebPreviewPositionStore? = null

        fun getInstance(context: Context): WebPreviewPositionStore {
            return instance ?: synchronized(this) {
                instance ?: WebPreviewPositionStore(context.applicationContext).also { instance = it }
            }
        }

        /**
         * host("https://www.avito.ru/profile") → "avito.ru": lowercased and
         * `www.`-stripped, so a position saved under www resumeds for the
         * bare host link too (and vice versa).
         */
        fun hostOf(url: String): String = try {
            URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** host → last URL on that host, insertion-ordered (oldest evicted first). */
    private val positions = LinkedHashMap<String, String>()

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "web-preview-position").apply { isDaemon = true }
    }

    init {
        load()
    }

    fun lastFor(url: String): String? {
        val host = hostOf(url)
        if (host.isEmpty()) return null
        synchronized(positions) { return positions[host] }
    }

    fun record(url: String) {
        val host = hostOf(url)
        // Only http(s) pages resume — file:// and minis:// previews are
        // one-shot documents (regenerated sandbox outputs), not sessions.
        if (host.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        io.execute {
            if (positions[host] == url) return@execute
            positions.remove(host) // re-insert to move to the tail = freshest
            positions[host] = url
            while (positions.size > MAX_HOSTS) {
                val eldest = positions.keys.firstOrNull() ?: break
                positions.remove(eldest)
            }
            persistLocked()
        }
    }

    private fun persistLocked() {
        try {
            val obj = JSONObject()
            synchronized(positions) {
                for ((host, url) in positions) obj.put(host, url)
            }
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
            synchronized(positions) {
                for (key in obj.keys()) positions[key] = obj.optString(key, "")
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
        }
    }
}
