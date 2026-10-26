package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()

    /**
     * [T-chat-link-not-found] A `minis://` link whose file is not there.
     *
     * Falling through to [Web] used to end in the in-app browser, which has
     * no idea what `minis://` means and reported it as a blocked external
     * link. A missing file is a different fact from a web page and deserves
     * its own outcome.
     */
    data class NotFound(val url: String, val detail: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — prefer a session-scoped resolver when
        //    the caller knows which chat this link belongs to. The global
        //    `PRootKernel.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }

        // [T-chat-link-global-fallback] A file produced by ANY session must be
        // reachable from any other. `attachments`, `offloads`, `workspace` and
        // `browser` are bind-mounted per session (files/minis-sessions/<sid>/),
        // so an attachment written by a background-task session is invisible
        // to the chat session that renders the link - it simply is not in that
        // directory. Verified on device: a 43 MB APK written at 09:06 existed
        // at 09:06 and the session's attachments directory was empty at 09:18.
        // Falling back to the global pool fixes exactly that case without
        // making attachments global, which would let two sessions collide on
        // one filename.
        val globalHit = resolveInGlobalPool(trimmed, scheme, context)
        if (globalHit != null) {
            FileItem.from(globalHit)?.let { return ChatLinkAction.SandboxFile(it) }
        }

        // [T-chat-link-not-found] Do not let an unresolvable `minis://` link
        // continue as if it were a web page: the in-app browser cannot load
        // our own scheme and reported it as a blocked external link, which is
        // both wrong and useless to the reader.
        if (scheme == "minis") {
            return ChatLinkAction.NotFound(
                trimmed,
                "Файл не найден: ${hostFile?.absolutePath ?: trimmed}",
            )
        }

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        // [T-telegram-in-app] Telegram links stay inside the app. A `tg://`
        // deep link was dispatched to the system handler, which sent the
        // user out into their Telegram client, and a `t.me` link was routed
        // the same way. Both have a public web form, so the link is
        // rewritten and opened in the in-app browser instead.
        com.openminis.app.ui.browser.TelegramLink.toWebUrl(trimmed)?.let {
            return ChatLinkAction.Web(it)
        }

        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                val decoded = runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
                val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"
                lookup(linuxPath)
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) null else File(java.net.URLDecoder.decode(path, "UTF-8"))
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    /**
     * [T-chat-link-global-fallback] Second chance for session-scoped paths,
     * looked up in the global pool under files/minis-global/.
     *
     * Only the four per-session subdirs are consulted, and only when [context]
     * is available — the global pool lives under the app's filesDir, which
     * needs a Context to locate. Returns null rather than throwing when the
     * name does not exist, and never returns a directory.
     */
    private fun resolveInGlobalPool(raw: String, scheme: String?, context: Context?): File? {
        val ctx = context ?: return null
        return resolveInGlobalPool(File(ctx.filesDir, "minis-global"), raw, scheme)
    }

    /**
     * [T-chat-link-global-fallback] Pure core of the global-pool lookup.
     *
     * Takes the global root instead of a Context so the rule can be unit
     * tested on a plain JVM - the project has no Robolectric dependency, and
     * this is the piece that actually decides whether a cross-session
     * attachment resolves.
     */
    internal fun resolveInGlobalPool(globalRoot: File, raw: String, scheme: String?): File? {
        val linuxPath = when (scheme) {
            "minis" -> {
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                val decoded = runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped)
                if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"
            }
            null -> if (raw.startsWith("/")) raw else return null
            else -> return null
        }
        // /var/minis/<subdir>/<tail> - only per-session subdirs are eligible.
        val rest = linuxPath.removePrefix("/var/minis/")
        val slash = rest.indexOf('/')
        val subdir = if (slash < 0) rest else rest.substring(0, slash)
        if (subdir !in PER_SESSION_SUBDIRS) return null
        val tail = if (slash < 0) "" else rest.substring(slash + 1)
        if (tail.isEmpty()) return null
        val base = File(globalRoot, subdir)
        val candidate = File(base, tail)
        // [T-chat-link-global-fallback] `File.isFile` resolves ".." through
        // the OS, so "attachments/../secret.txt" would happily open a file
        // outside the global pool. The per-session resolver in PRootKernel
        // already guards its tail; this is the same rule for this path.
        val inside = runCatching {
            val cb = base.canonicalFile
            val ct = candidate.canonicalFile
            ct.absolutePath.startsWith(cb.absolutePath + File.separator)
        }.getOrDefault(false)
        if (!inside) {
            android.util.Log.w("ChatLinkDiag", "global fallback refused traversal: $raw")
            return null
        }
        return if (candidate.isFile) candidate else null
    }

    /** [T-chat-link-global-fallback] Mirrors PRootKernel.perSessionSubdirs. */
    internal val PER_SESSION_SUBDIRS = setOf("attachments", "offloads", "workspace", "browser")

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
