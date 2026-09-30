package com.openminis.app.data.db

import androidx.room.ColumnInfo

/**
 * [T-retry-instant] Light projection for cutoff resolution: id + sort
 * order only. See ChatDao.rowIdsAndSortOrders — the retry path must never
 * load partsJson bodies just to decide WHERE to cut.
 */
data class MessageIdSort(
    val id: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)
