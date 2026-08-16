package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Pure planning and lossless rendering for model-based context compaction. */
object ModelCompaction {
    data class Source(val id: String, val text: String)

    data class Segment(
        val sourceId: String,
        val sourceIndex: Int,
        val segmentIndex: Int,
        val segmentCount: Int,
        val text: String,
    ) {
        fun render(): String = buildString {
            append("<source id=\"").append(sourceId)
                .append("\" part=\"").append(segmentIndex + 1)
                .append('/').append(segmentCount).append("\">\n")
            append(text)
            append("\n</source>")
        }
    }

    data class Batch(val segments: List<Segment>) {
        fun render(): String = segments.joinToString("\n\n") { it.render() }
    }

    data class Budget(
        val contextWindowTokens: Int,
        val fixedPromptTokens: Int,
        val maxBatchTokens: Int,
        val maxOutputTokens: Int,
        val safetyTokens: Int,
    )

    /**
     * UTF-8 bytes are a conservative upper bound for BPE token count: every
     * token consumes at least one source byte. This intentionally trades a few
     * extra model calls for never discovering an oversized chunk at the API.
     */
    fun conservativeTokenUpperBound(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun budget(contextWindowTokens: Int, fixedPrompt: String): Budget {
        val window = contextWindowTokens.coerceAtLeast(8_192)
        val fixed = conservativeTokenUpperBound(fixedPrompt)
        val safety = maxOf(1_024, window / 10)
        val output = minOf(8_192, maxOf(1_024, window / 8))
        val available = window - fixed - safety - output
        require(available >= 1_024) {
            "Model context window is too small for safe compaction (window=$window, fixed=$fixed)"
        }
        return Budget(window, fixed, available, output, safety)
    }

    /** Render every text-bearing part in full. Binary media becomes metadata. */
    fun sourcesFrom(messages: List<LLMMessage>): List<Source> = messages.mapIndexed { index, message ->
        val id = message.dbMessageId?.takeIf { it.isNotBlank() } ?: "history-$index"
        Source(id, renderMessage(index, id, message))
    }

    private fun renderMessage(index: Int, id: String, message: LLMMessage): String = buildString {
        append("[MESSAGE index=").append(index)
            .append(" id=").append(id)
            .append(" role=").append(message.role.value).append("]\n")

        val textParts = message.contentParts.filterIsInstance<AgentContentPart.Text>()
        if (textParts.isEmpty()) {
            if (message.content.isNotEmpty()) append("[TEXT]\n").append(message.content).append('\n')
        } else {
            for (part in textParts) append("[TEXT]\n").append(part.text).append('\n')
        }
        for (part in message.contentParts) {
            when (part) {
                is AgentContentPart.Text -> Unit
                is AgentContentPart.ToolUse -> {
                    append("[TOOL_USE id=").append(part.id)
                        .append(" name=").append(part.name).append("]\n")
                    append(part.input.toString()).append('\n')
                }
                is AgentContentPart.ToolResult -> {
                    append("[TOOL_RESULT id=").append(part.id)
                        .append(" name=").append(part.name)
                        .append(" error=").append(part.isError).append("]\n")
                    append(part.content).append('\n')
                    if (part.imageData != null) {
                        append("[TOOL_RESULT_IMAGE mime=").append(part.imageMimeType ?: "unknown")
                            .append(" bytes=").append(part.imageData.size)
                        part.imageLinuxPath?.let { append(" path=").append(it) }
                        append("]\n")
                    }
                }
                is AgentContentPart.ImageData -> {
                    append("[IMAGE mime=").append(part.mimeType)
                        .append(" bytes=").append(part.data.size)
                    part.linuxPath?.let { append(" path=").append(it) }
                    append("]\n")
                }
            }
        }
        message.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
            append("[REASONING_CONTENT]\n").append(it).append('\n')
        }
        append("[/MESSAGE]")
    }

    /**
     * Split before the first model request. Every source character appears in
     * exactly one segment; no `take(...)` or ellipsis is used anywhere.
     */
    fun planBatches(sources: List<Source>, maxBatchTokens: Int): List<Batch> {
        require(maxBatchTokens >= 512) { "maxBatchTokens must be at least 512" }
        if (sources.isEmpty()) return emptyList()

        val allSegments = mutableListOf<Segment>()
        for ((sourceIndex, source) in sources.withIndex()) {
            val pieces = splitText(source.text, (maxBatchTokens - 256).coerceAtLeast(256))
            for ((segmentIndex, text) in pieces.withIndex()) {
                allSegments += Segment(
                    sourceId = source.id,
                    sourceIndex = sourceIndex,
                    segmentIndex = segmentIndex,
                    segmentCount = pieces.size,
                    text = text,
                )
            }
        }

        val batches = mutableListOf<Batch>()
        var current = mutableListOf<Segment>()
        for (segment in allSegments) {
            val candidate = Batch(current + segment)
            if (current.isNotEmpty() && conservativeTokenUpperBound(candidate.render()) > maxBatchTokens) {
                batches += Batch(current)
                current = mutableListOf(segment)
            } else {
                current += segment
            }
            require(conservativeTokenUpperBound(Batch(current).render()) <= maxBatchTokens) {
                "A single source segment exceeds the compaction budget"
            }
        }
        if (current.isNotEmpty()) batches += Batch(current)
        return batches
    }

    private fun splitText(text: String, maxUtf8Bytes: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        if (conservativeTokenUpperBound(text) <= maxUtf8Bytes) return listOf(text)

        val pieces = mutableListOf<String>()
        var start = 0
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val cpBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes > 0 && bytes + cpBytes > maxUtf8Bytes) {
                pieces += text.substring(start, index)
                start = index
                bytes = 0
            }
            bytes += cpBytes
            index += chars
        }
        pieces += text.substring(start)
        return pieces
    }
}
