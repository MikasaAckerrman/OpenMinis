package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-letta-core-memory] One core-memory block. Mirrors Letta's core-memory
 * design (supermemoryai/Letta block model): the agent curates a SMALL set of
 * standing facts that must hold for every turn — unlike the daily-log
 * (memory_write), which is an append-only stream searched on demand.
 *
 * @param id short stable identifier the model uses to edit the block
 *   ("b1".."b16"). Ids are NOT recycled: deleting a block retires its id.
 * @param label one-line human/model-readable title, shown in the injection
 *   header and the settings sheet.
 * @param value the fact itself. Capped at [CoreMemoryStore.MAX_VALUE_CHARS]
 *   on write — the store rejects oversized values with an explicit error so
 *   the model shortens instead of silently truncating.
 * @param pinned pinned blocks survive the injection-budget truncation and
 *   float to the top of the header.
 * @param lastEditedTurn who/when last edited this block ("23:15 model"),
 *   surfaced to the model in memory_blocks_view for staleness reasoning.
 */
data class CoreBlock(
    val id: String,
    val label: String,
    val value: String,
    val pinned: Boolean = false,
    val lastEditedTurn: String = "",
)

/**
 * [T-letta-core-memory] Persistence + in-process cache for the core-memory
 * blocks. Storage decision (per plan 24.09): a single JSON file at
 * `filesDir/memory/core_blocks.json` — blocks are few (≤16) and small, edited
 * rarely; a Room table would cost a schema migration for zero query benefit.
 * Atomic write (tmp + rename) so a process death mid-write can never corrupt
 * the previous state — the same discipline as the crash journals.
 *
 * All reads go through the [blocks] cache: the request path calls
 * [injectHeaderText] several times per turn (context estimation is hot), so
 * a disk read per call is unacceptable. Single-writer (the tool executes on
 * the agent loop coroutine); [synchronized] guards the cache/file pair.
 */
object CoreMemoryStore {
    /** Max blocks — Letta's own core-memory default. Keeps the header bounded. */
    const val MAX_BLOCKS = 16

    /** Per-block value cap. Reject (not truncate) on write — the model learns. */
    const val MAX_VALUE_CHARS = 4000

    /** Per-block label cap. */
    const val MAX_LABEL_CHARS = 80

    /**
     * Total injection budget. Pinned blocks first; when the budget runs out
     * the header is cut with a visible marker pointing at memory_blocks_view.
     */
    const val INJECTION_BUDGET_CHARS = 6000

    private const val FILE_NAME = "core_blocks.json"
    private const val HEADER_TITLE = "== CORE MEMORY =="

    @Volatile
    private var dir: File? = null

    @Volatile
    private var cached: List<CoreBlock>? = null

    /** Capture the storage dir. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        primeDir(File(context.applicationContext.filesDir, "memory"))
    }

    /**
     * Test seam: set/replace the storage dir and drop the cache. JVM tests
     * have no Context — they point the store at a JUnit TemporaryFolder (or
     * null for cache-only mode) and get a deterministic fresh state.
     */
    internal fun primeDirForTest(directory: File?) {
        synchronized(this) {
            dir = directory
            cached = null
        }
    }

    /** Current blocks (pinned first, then id order — the injection order). */
    fun blocks(): List<CoreBlock> {
        cached?.let { return it }
        synchronized(this) {
            cached ?: run {
                cached = load()
                cached!!
            }
            return cached!!
        }
    }

    /**
     * Upsert by [id]. Returns null on success or a human-readable error the
     * tool surfaces to the model (caps, unknown id, duplicate label).
     */
    fun upsert(
        id: String,
        label: String,
        value: String,
        pinned: Boolean,
        editedTurn: String,
    ): String? {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return "id must not be empty"
        val current = blocks()
        val existing = current.firstOrNull { it.id == cleanId }
        if (existing == null && current.size >= MAX_BLOCKS) {
            return "core memory is full ($MAX_BLOCKS blocks) — merge or delete first"
        }
        if (label.length > MAX_LABEL_CHARS) {
            return "label too long (${label.length} > $MAX_LABEL_CHARS chars)"
        }
        if (value.length > MAX_VALUE_CHARS) {
            return "value too long (${value.length} > $MAX_VALUE_CHARS chars) — split or shorten"
        }
        val next = current
            .map { b ->
                if (b.id == cleanId) b.copy(
                    label = label,
                    value = value,
                    pinned = pinned,
                    lastEditedTurn = editedTurn,
                ) else b
            }
            .let { if (existing == null) it + CoreBlock(cleanId, label, value, pinned, editedTurn) else it }
        persist(next)
        return null
    }

    /** Delete by id; null on success or an error message. */
    fun delete(id: String): String? {
        val cleanId = id.trim()
        val current = blocks()
        if (current.none { it.id == cleanId }) return "no block with id=$cleanId"
        persist(current.filterNot { it.id == cleanId })
        return null
    }

    /**
     * The injection header text, or null when there is nothing to inject
     * (empty store) — the caller treats null as "skip the layer, zero cost".
     * Pinned blocks first; the whole header is bounded by
     * [INJECTION_BUDGET_CHARS] with a visible cut marker.
     */
    fun injectHeaderText(): String? {
        val ordered = blocks().sortedWith(compareByDescending<CoreBlock> { it.pinned }.thenBy { it.id })
        if (ordered.isEmpty()) return null
        val sb = StringBuilder(HEADER_TITLE).append('\n')
        var budget = INJECTION_BUDGET_CHARS - HEADER_TITLE.length - 1
        var cut = false
        for (b in ordered) {
            val line = "- [${b.id}] ${b.label}: ${b.value}\n"
            if (line.length > budget) {
                cut = true
                break
            }
            sb.append(line)
            budget -= line.length
        }
        if (cut) {
            sb.append("(budget reached — memory_blocks_view shows all blocks)\n")
        }
        return sb.toString()
    }

    /** Full listing for memory_blocks_view (no budget cut). */
    fun viewText(): String {
        val ordered = blocks().sortedWith(compareByDescending<CoreBlock> { it.pinned }.thenBy { it.id })
        if (ordered.isEmpty()) return "core memory is empty (0 blocks)"
        return ordered.joinToString("\n") { b ->
            "- [${b.id}]${if (b.pinned) " (pinned)" else ""} ${b.label}\n  ${b.value}\n  last edited: ${b.lastEditedTurn}"
        }
    }

    // ---- persistence ----

    private fun file(): File? = dir?.let { File(it, FILE_NAME) }

    @Synchronized
    private fun persist(next: List<CoreBlock>) {
        val f = file() ?: return // not primed — keep cache-only (tests)
        val json = JSONObject().apply {
            put("schema", 1)
            put("blocks", JSONArray().apply { next.forEach { put(blockToJson(it)) } })
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                // Cross-filesystem rename fallback: write-through then delete.
                f.writeText(json.toString())
                tmp.delete()
            }
        }
        cached = next
    }

    private fun load(): List<CoreBlock> {
        val f = file() ?: return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONObject(text).optJSONArray("blocks") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CoreBlock(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    value = o.optString("value"),
                    pinned = o.optBoolean("pinned"),
                    lastEditedTurn = o.optString("lastEditedTurn"),
                )
            }.filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun blockToJson(b: CoreBlock): JSONObject = JSONObject().apply {
        put("id", b.id)
        put("label", b.label)
        put("value", b.value)
        put("pinned", b.pinned)
        put("lastEditedTurn", b.lastEditedTurn)
    }
}

/**
 * [T-letta-core-memory] Wire-only injection of the core-memory header into
 * the outgoing payload head — the 4th layer of effectiveAgentHistory,
 * BEFORE the pre-anchor region (plan 24.09). Pure function over immutable
 * message lists: JVM-testable without Android.
 *
 * Placement mirrors the compaction-summary discipline: the header is
 * prepended as a text part to the FIRST message's contentParts rather than
 * a synthetic standalone turn, preserving strict role alternation. The
 * injected part is NEVER persisted — agentHistory stays the source of truth,
 * so reloads and compaction see no core-memory residue (the summary layer
 * follows the same wire-only rule).
 */
object CoreMemoryInjector {

    /**
     * Prepend [headerText] to the first message. Returns [history] unchanged
     * when it is empty (a fresh session's first user message arrives with the
     * first send — the injection lands there).
     */
    fun inject(history: List<LLMMessage>, headerText: String): List<LLMMessage> {
        if (history.isEmpty()) return history
        val first = history.first()
        val injected = first.copy(
            contentParts = listOf(AgentContentPart.Text(headerText)) + first.contentParts,
            content = headerText + first.content,
        )
        return listOf(injected) + history.drop(1)
    }
}
