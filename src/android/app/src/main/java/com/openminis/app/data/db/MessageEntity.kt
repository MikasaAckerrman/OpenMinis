package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["session_id", "sort_order"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,        // milliseconds
    // [T-error-persist-android] Terminal error sticker for an assistant turn
    // (mirrors iOS messages.error_info / ChatMessage.error). Null for normal
    // rows; device-local, never synced to iCloud.
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    // [B-dedup-acceptor] PromptIds of the queue entries drained INTO this row,
    // as a JSON array string. Written by the SAME insert as the row itself —
    // the row's existence IS the acceptance marker (atomic sink): there is no
    // window where a row is durable but its promptIds are not, or vice versa.
    // Acceptor-side dedup (drainQueuedPrompts / injectQueuedPromptsAsNewTurn /
    // session restore) consults it to drop persisted-queue entries whose
    // message already landed — the at-least-once duplicate of
    // queue-acceptance-spec scenario B (process death between appendMessage
    // and persistPromptQueue). Null for rows not built from the queue
    // (normal sends, assistant turns, tool results).
    @ColumnInfo(name = "source_prompt_ids") val sourcePromptIds: String? = null,
)
