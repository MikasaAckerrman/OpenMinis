package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import com.openminis.app.data.model.AgentGraph

/**
 * Room entity for AgentGraph. Stored as JSON blob for schema flexibility.
 */
@Entity(tableName = "agent_graphs")
data class AgentGraphEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val jsonConfig: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toDomain(json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }): AgentGraph =
        json.decodeFromString<AgentGraph>(jsonConfig)

    companion object {
        fun fromDomain(graph: AgentGraph, json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }): AgentGraphEntity {
            return AgentGraphEntity(
                id = graph.id,
                name = graph.name,
                version = graph.version,
                jsonConfig = json.encodeToString(graph),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }

        fun toEntity(graph: AgentGraph, json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }): AgentGraphEntity {
            return AgentGraphEntity(
                id = graph.id,
                name = graph.name,
                version = graph.version,
                jsonConfig = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }.encodeToString(graph),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}