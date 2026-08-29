package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-deleted-archive-rpc] Read/restore access to the `deleted_messages`
 * archive that [com.openminis.app.data.db.ChatDao.archiveAndDeleteMessagesAfter]
 * fills. The archive existed since 2026-08-25 but had NO reader at all: rows
 * went in and nothing could ever get them out, so a session whose tail was
 * wrongly truncated stayed truncated even though the data was still on disk.
 */
internal object DeletedMessagesMethods {

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    /** `chat.deleted.list` — archived rows for a session. */
    suspend fun list(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val includeParts = params.optBoolean("includeParts", false)
        val dao = app(context).chatRepository.dao
        val rows = dao.loadDeletedMessages(sessionId)
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject().apply {
                    put("archiveId", r.archiveId)
                    put("messageId", r.messageId)
                    put("role", r.role)
                    put("sortOrder", r.sortOrder)
                    put("createdAt", r.createdAt)
                    put("deletedAt", r.deletedAt)
                    put("reason", r.archiveReason)
                    put("partsLength", r.partsJson.length)
                    if (includeParts) put("partsJson", r.partsJson)
                },
            )
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("count", arr.length())
            put("messages", arr)
        }
    }

    /**
     * `chat.deleted.restore` — copy archived rows back into `messages`.
     *
     * Restores by archive batch (`deletedAt`) so one wrong retry can be undone
     * as a unit, or by explicit `archiveIds`. Existing live rows are never
     * overwritten: an id already present in `messages` is skipped and reported,
     * because a later turn legitimately reusing that id must win over an old
     * archive copy.
     */
    suspend fun restore(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val dao = app(context).chatRepository.dao
        val all = dao.loadDeletedMessages(sessionId)
        if (all.isEmpty()) {
            throw RPCException(-32602, "No archived messages for this session")
        }

        val wantedIds = params.optJSONArray("archiveIds")?.let { a ->
            (0 until a.length()).mapTo(mutableSetOf()) { a.getString(it) }
        }
        val batchDeletedAt = if (params.has("deletedAt")) params.getLong("deletedAt") else null
        val selected = all.filter { r ->
            (wantedIds == null || r.archiveId in wantedIds) &&
                (batchDeletedAt == null || r.deletedAt == batchDeletedAt)
        }
        if (selected.isEmpty()) {
            throw RPCException(-32602, "Filter matched no archived rows")
        }

        val liveIds = dao.loadMessages(sessionId).mapTo(mutableSetOf()) { it.id }
        val (skipped, toInsert) = selected.partition { it.messageId in liveIds }
        val dryRun = params.optBoolean("dryRun", false)

        if (!dryRun && toInsert.isNotEmpty()) {
            dao.insertMessages(
                toInsert.map { r ->
                    MessageEntity(
                        id = r.messageId,
                        sessionId = r.sessionId,
                        role = r.role,
                        partsJson = r.partsJson,
                        createdAt = r.createdAt,
                        tokenUsage = r.tokenUsage,
                        sortOrder = r.sortOrder,
                        reasoningContent = r.reasoningContent,
                        streamInterruptCount = r.streamInterruptCount,
                        updatedAt = r.updatedAt,
                        errorInfo = r.errorInfo,
                    )
                },
            )
        }

        return JSONObject().apply {
            put("sessionId", sessionId)
            put("dryRun", dryRun)
            put("restored", toInsert.size)
            put("skippedAlreadyLive", skipped.size)
            put("archivedTotal", all.size)
        }
    }
}
