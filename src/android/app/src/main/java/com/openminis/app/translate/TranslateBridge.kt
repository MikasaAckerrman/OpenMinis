package com.openminis.app.translate

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline EN↔RU translation, shared with the web tool and the CLI.
 *
 * WHY A HEADLESS WEBVIEW AND NOT A NATIVE PORT.
 * The engine is Bergamot compiled to WASM (Mozilla's Firefox Translations).
 * There is no JVM build of it, and the models are 34 MB + 17 MB of
 * intgemm-quantized weights that only that runtime reads. A WebView is the
 * only place on Android where this runs at all.
 *
 * WHY THE ASSETS LIVE OUTSIDE THE APK.
 * `/var/minis/shared` is already bind-mounted to
 * `filesDir/minis-global/shared` (see PRootKernel.registerGlobalBindMounts),
 * so the app can read those files directly. Loading `translate.js` and
 * `glossary.js` from there instead of `assets/` means:
 *   - the APK does not grow by 60 MB of models;
 *   - a glossary edit takes effect without a rebuild;
 *   - one implementation serves the web page, the CLI and native callers,
 *     so a fix cannot land in two of the three and be forgotten in the last.
 *
 * WARM-UP IS NOT OPTIONAL. Measured on a Snapdragon 8 Gen 3: the first
 * translation costs ~1300 ms because the model file is parsed and each
 * worker in the pool builds its graph; every call afterwards is 45–110 ms.
 * [prewarm] is therefore called as early as the caller can afford it — for
 * PROCESS_TEXT that is `onCreate`, before the text has even been read.
 *
 * THREADING. WebView is main-thread-only. Every `evaluateJavascript` call
 * is hopped onto Dispatchers.Main; results arrive on a WebView-internal
 * thread and are handed back through a per-request [CompletableDeferred].
 */
object TranslateBridge {

    private const val TAG = "TranslateBridge"

    /** Host name the asset loader answers on. Must be an https URL, otherwise
     *  WebView applies file:// restrictions and ES module imports fail. */
    private const val HOST = "minis-translate.androidplatform.net"

    private const val BRIDGE_FILE = "bridge.html"

    /** Enough for a screenful of text on a cold engine; a warm call is ~100 ms. */
    private const val TIMEOUT_MS = 60_000L

    private var webView: WebView? = null
    private val booting = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()

    /** Completes when the engine reports it is warm. Callers that only need a
     *  result never have to wait on this — [translate] queues regardless. */
    @Volatile
    private var readySignal = CompletableDeferred<Unit>()

    data class Result(
        val text: String,
        val ms: Int,
        val from: String,
        val to: String,
        val cached: Boolean,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** Directory the shared toolbox lives in, host-side. Mirrors the Linux
     *  path `/var/minis/shared/toolbox` the shell and CLI see. */
    private fun toolboxDir(context: Context): File =
        File(context.filesDir, "minis-global/shared/toolbox")

    fun isAvailable(context: Context): Boolean =
        File(toolboxDir(context), BRIDGE_FILE).isFile &&
            File(toolboxDir(context), "translate.js").isFile

    /**
     * Bring the engine up and warm the given direction. Safe to call more than
     * once — subsequent calls are cheap. Returns as soon as the WebView exists;
     * warming continues in the background.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun prewarm(context: Context, from: String = "en", to: String = "ru") {
        if (!isAvailable(context)) {
            AppLogger.warning(TAG, "toolbox assets missing at ${toolboxDir(context)}")
            return
        }
        if (webView != null) {
            withContext(Dispatchers.Main) { warmDirection(from, to) }
            return
        }
        if (!booting.compareAndSet(false, true)) return

        withContext(Dispatchers.Main) {
            val dir = toolboxDir(context)
            val loader = WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(context, dir))
                .build()

            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            // The engine spawns Workers that fetch the model files; both need
            // the asset loader, which only answers same-origin requests.
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.settings.domStorageEnabled = true      // translate.js caches in localStorage

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    warmDirection(from, to)
                }
            }
            wv.addJavascriptInterface(Callback(), "MinisTr")
            webView = wv
            wv.loadUrl("https://$HOST/$BRIDGE_FILE")
        }
    }

    /** Wait until the engine is warm, or give up after [timeoutMs].
     *  Used by callers that want to show a spinner exactly once. */
    suspend fun awaitReady(timeoutMs: Long = TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { readySignal.await(); true } ?: false

    /**
     * Translate [text]. Direction is detected from the alphabet when [from] or
     * [to] is null. Never throws: failures come back as [Result.error] so a
     * caller wiring this into a system menu cannot crash the host app.
     */
    suspend fun translate(
        context: Context,
        text: String,
        from: String? = null,
        to: String? = null,
    ): Result {
        if (text.isBlank()) return Result("", 0, from ?: "", to ?: "", false, "empty text")
        prewarm(context, from ?: "en", to ?: "ru")
        val wv = webView
            ?: return Result("", 0, "", "", false, "engine unavailable")

        val args = JSONObject().apply {
            put("text", text)
            from?.let { put("from", it) }
            to?.let { put("to", it) }
        }

        val deferred = CompletableDeferred<Result>()
        val id = withContext(Dispatchers.Main) {
            // TR.translate returns the request id synchronously and delivers
            // the result through MinisTr.onResult, so a long translation does
            // not sit inside an evaluateJavascript callback.
            val slot = CompletableDeferred<Int>()
            wv.evaluateJavascript(
                "TR.translate(${JSONObject.quote(args.toString())})"
            ) { raw ->
                val cleaned = raw?.trim('"')?.trim()
                slot.complete(cleaned?.toIntOrNull() ?: -1)
            }
            slot.await()
        }
        if (id <= 0) return Result("", 0, "", "", false, "bridge rejected the request")
        pending[id] = deferred

        return withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            ?.also { pending.remove(id) }
            ?: Result("", 0, "", "", false, "timed out").also { pending.remove(id) }
    }

    private fun warmDirection(from: String, to: String) {
        webView?.evaluateJavascript(
            "TR.warm(${JSONObject.quote(from)},${JSONObject.quote(to)})", null)
    }

    /** Release the WebView. Call when the host activity is finishing and no
     *  further translation is expected — the engine holds ~50 MB. */
    fun release() {
        val wv = webView ?: return
        webView = null
        booting.set(false)
        readySignal = CompletableDeferred()
        pending.clear()
        wv.post {
            wv.stopLoading()
            wv.destroy()
        }
    }

    /** JS → Kotlin. Methods run on a WebView-internal thread. */
    private class Callback {
        @JavascriptInterface
        fun onLoaded() {
            AppLogger.info(TAG, "bridge script loaded")
        }

        @JavascriptInterface
        fun onReady() {
            AppLogger.info(TAG, "engine warm")
            readySignal.complete(Unit)
        }

        @JavascriptInterface
        fun onError(message: String) {
            AppLogger.error(TAG, "engine error: $message")
            // Unblock waiters: a failed warm-up must not hang a caller forever.
            readySignal.complete(Unit)
        }

        @JavascriptInterface
        fun onResult(id: Int, json: String) {
            val slot = pending.remove(id) ?: return
            slot.complete(parse(json))
        }

        private fun parse(json: String): Result = try {
            val o = JSONObject(json)
            if (o.optBoolean("ok", false)) {
                Result(
                    text = o.optString("text"),
                    ms = o.optInt("ms"),
                    from = o.optString("from"),
                    to = o.optString("to"),
                    cached = o.optBoolean("cached"),
                )
            } else {
                Result("", 0, "", "", false, o.optString("error", "unknown error"))
            }
        } catch (e: Throwable) {
            Result("", 0, "", "", false, "bad response: ${e.message}")
        }
    }
}
