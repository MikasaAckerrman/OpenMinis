package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-no-destructive-retry] Archive of message rows that retry/edit used to
 * delete outright. The tail-truncation primitive [ChatDao.deleteMessagesAfter]
 * copies every row it is about to remove into this table first, inside one
 * transaction, so a retry / edit / rerun can no longer make the user's earlier
 * turns unrecoverable — the exact failure that wiped the HUD session's
 * 17–22 Aug work.
 *
 * Deliberately NOT a foreign-key child of `sessions`: the archive must outlive
 * even an explicit session delete, and CASCADE would defeat the whole point.
 * `session_id` is a plain indexed column, not a FK.
 *
 * The primary key is a synthetic `archive_id`, NOT the original message `id`,
 * because the same logical message can be archived more than once (edit a
 * turn, get a new tail, edit again) and we keep every version. The original id
 * is preserved in `message_id` for restore.
 */
@Entity(
    tableName = "deleted_messages",
    indices = [
        Index(value = ["session_id", "sort_order"]),
        Index(value = ["message_id"]),
        Index(value = ["deleted_at"]),
    ],
)
data class DeletedMessageEntity(
    @PrimaryKey @ColumnInfo(name = "archive_id") val archiveId: String,
    // ─── verbatim copy of the original messages row ───────────────────────
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    // ─── archive bookkeeping ──────────────────────────────────────────────
    /** When the row was archived+deleted (epoch ms). */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    /** Which operation removed it: "retry" | "edit" | "rerun" | "retryLast". */
    @ColumnInfo(name = "archive_reason") val archiveReason: String,
) {
    companion object {
        /**
         * Copy a live [MessageEntity] into an archive row verbatim. Every
         * message field is preserved; only the archive bookkeeping
         * ([archiveId], [deletedAt], [archiveReason]) is added. Kept as a
         * pure function so it is unit-testable without a Room/Android runtime
         * — the invariant "archiving never mutates or drops message data" is
         * exactly what the HUD-loss regression needs guarded.
         */
        fun fromMessage(
            m: MessageEntity,
            deletedAt: Long,
            reason: String,
            archiveId: String = java.util.UUID.randomUUID().toString(),
        ): DeletedMessageEntity = DeletedMessageEntity(
            archiveId = archiveId,
            messageId = m.id,
            sessionId = m.sessionId,
            role = m.role,
            partsJson = m.partsJson,
            createdAt = m.createdAt,
            tokenUsage = m.tokenUsage,
            sortOrder = m.sortOrder,
            reasoningContent = m.reasoningContent,
            streamInterruptCount = m.streamInterruptCount,
            updatedAt = m.updatedAt,
            errorInfo = m.errorInfo,
            deletedAt = deletedAt,
            archiveReason = reason,
        )
    }
}
