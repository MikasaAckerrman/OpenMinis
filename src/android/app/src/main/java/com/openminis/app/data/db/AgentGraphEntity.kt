package com.openminis.app.data.db

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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toDomain(json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }): AgentGraph =
        json.decodeFromString<AgentGraph>(jsonConfig)

    companion object {
        fun fromDomain(graph: AgentGraph, json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }): AgentGraphEntity {
            return AgentGraphEntity(
                id = graph.id,
                name = graph.name,
                version = graph.version,
                jsonConfig = json.encodeToString(graph),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}