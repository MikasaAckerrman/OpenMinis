package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.data.db.AgentGraphDao
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.model.AgentGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for AgentGraph persistence.
 * Uses Room DB (provider.db) with JSON mirror for backward compatibility.
 */
class AgentGraphRepository(private val context: Context) {

    private val dao: AgentGraphDao =
        ProviderDatabase.getInstance(context).agentGraphDao()

    /**
     * Save or update an agent graph.
     */
    suspend fun saveGraph(graph: AgentGraph) = withContext(Dispatchers.IO) {
        dao.upsert(graph.toEntity())
    }

    /**
     * Load a graph by ID.
     */
    suspend fun loadGraph(id: String): AgentGraph? = withContext(Dispatchers.IO) {
        dao.loadById(id)?.toDomain()
    }

    /**
     * List all graphs.
     */
    suspend fun listGraphs(): List<AgentGraph> = withContext(Dispatchers.IO) {
        dao.loadAll().map { it.toDomain() }
    }

    /**
     * Delete a graph by ID.
     */
    suspend fun deleteGraph(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    /**
     * Get graph names for UI display.
     */
    suspend fun listGraphNames(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        dao.loadAll().map { it.id to it.name }
    }
}