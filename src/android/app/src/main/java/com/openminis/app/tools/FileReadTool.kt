package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

object FileReadTool {
    const val NAME = "file_read"

    // [T-pdf-fileread] True when the request targets a .pdf — the dispatch
    // layer reroutes it to executePdf (needs the suspend coordinator).
    fun isPdf(argsJson: String): Boolean =
        runCatching { JSONObject(argsJson).optString("path", "") }
            .getOrDefault("").lowercase().endsWith(".pdf")

    /**
     * [T-pdf-fileread] PDF text extraction: page text via the guest's
     * pdftotext (poppler-utils). Self-healing contract — when the binary
     * is absent the result tells the model the exact bootstrap command
     * (apk add poppler-utils via shell_execute), so the FIRST pdf-read
     * in a fresh rootfs teaches itself the tool.
     */
    suspend fun executePdf(
        argsJson: String,
        sessionId: String,
        context: android.content.Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("Error: malformed arguments", false)
        }
        val path = args.optString("path", "").trim()
        val toolTitle = args.optString("tool_title", "Extract PDF text")
        if (path.isEmpty()) return ToolExecutionResult("Error: 'path' is required", false)
        // Host existence check for a clean early error; the guest command
        // uses the SAME path string the model sent (guest-space == the
        // tool's path namespace).
        val hostFile = com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(
            sessionId, path, context)
        if (hostFile == null || !hostFile.exists()) {
            return ToolExecutionResult("Error: file not found: $path", false, toolTitle = toolTitle)
        }
        val maxLength = args.optInt("max_length", 15000).coerceIn(1000, 60000)
        val quoted = "'" + path.replace("'", "'\\''") + "'"
        val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = "command -v pdftotext >/dev/null 2>&1 && pdftotext -layout $quoted - 2>/dev/null || echo PDFTEXT_MISSING",
            timeout = 30_000L,
        )
        val out = result.output.trim()
        if (out == "PDFTEXT_MISSING" || out.isEmpty()) {
            return ToolExecutionResult(
                "PDF text extraction unavailable: pdftotext is not installed in the guest.\n" +
                    "Bootstrap once with shell_execute: apk add poppler-utils\n" +
                    "Then retry file_read on the same path.",
                false, toolTitle = toolTitle)
        }
        val capped = if (out.length > maxLength) out.take(maxLength) + "\n…(truncated)" else out
        val pages = result.output.split("\u000C").size
        return ToolExecutionResult(
            "[$path | PDF | ~$pages page(s) | text extracted]\n\n$capped",
            true, toolTitle = toolTitle)
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files. PDF: text layer is extracted automatically (needs pdftotext in the guest — the error tells you the bootstrap command).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (default: 15000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
            "force_read" to AgentToolParam("boolean", "Bypass the unchanged-file cache: when the same path+range was already read and the file is byte-identical, file_read returns a short 'UNCHANGED' stub instead of the content. Set true to always receive full content."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length", "force_read"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val MAX_LENGTH_HARD_CAP = 80_000
            val maxLength = args.optInt("max_length", 15000).coerceAtMost(MAX_LENGTH_HARD_CAP)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val size = file.length()

            // Binary detection: check first 8192 bytes for null bytes
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192, size.toInt()))
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }

            if (isBinary) {
                // [T-pdf-fileread] PDF text extraction happens in the
                // DISPATCH layer (needs the suspend coordinator) — see
                // isPdf()/executePdf(). Every other binary stays a stub.
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            val allLines = file.readLines()
            val totalLines = allLines.size

            val requestedLines = if (args.has("lines")) args.optInt("lines") else null

            val selectedLines = if (direction == "tail") {
                val count = requestedLines ?: totalLines
                val start = (totalLines - count).coerceAtLeast(0)
                allLines.subList(start, totalLines)
            } else {
                val start = (offset - 1).coerceIn(0, totalLines)
                val end = if (requestedLines != null) {
                    (start + requestedLines).coerceAtMost(totalLines)
                } else {
                    totalLines
                }
                allLines.subList(start, end)
            }

            val showStart = if (direction == "tail") {
                (totalLines - selectedLines.size) + 1
            } else {
                offset
            }
            val showEnd = showStart + selectedLines.size - 1

            var content = selectedLines.joinToString("\n")
            if (content.length > maxLength) {
                content = content.take(maxLength) + "\n... (truncated)"
            }

            val header = "[$path | $size bytes | $totalLines lines | showing $showStart-$showEnd of $totalLines]"

            // [T-fileread-cache] Wishlist No.8: identical re-reads of an
            // UNCHANGED file return a stub. The fingerprint covers the full
            // file (any edit anywhere invalidates every shape for the path);
            // the key covers the exact request shape (a new offset is a new
            // question). The model keeps its earlier content and learns the
            // one fact it asked for: nothing changed.
            val contentSha = FileReadCache.sha256(allLines.joinToString("\n"))
            val requestShape = "o=$offset;l=${requestedLines ?: -1};d=$direction;m=$maxLength"
            val cacheKey = FileReadCache.key(sessionId, path, requestShape)
            val forceRead = args.optBoolean("force_read", false)
            if (!forceRead) {
                val hit = FileReadCache.lookup(cacheKey, contentSha)
                if (hit != null) {
                    val minsAgo = maxOf(0L, (System.currentTimeMillis() - hit.readAtMs) / 60_000)
                    return ToolExecutionResult(
                        "$header\n[UNCHANGED since your read ${minsAgo}m ago " +
                            "(sha256 ${contentSha.take(12)}, identical request). Content elided to save " +
                            "context — reason from your earlier read, or re-issue with force_read=true.]",
                        true, toolTitle = toolTitle,
                    )
                }
            }
            FileReadCache.record(cacheKey, contentSha, System.currentTimeMillis())

            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }
}
