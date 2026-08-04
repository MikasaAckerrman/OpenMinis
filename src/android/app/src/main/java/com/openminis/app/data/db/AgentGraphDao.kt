package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentGraphDao {

    @Query("SELECT * FROM agent_graphs ORDER BY updated_at DESC")
    suspend fun loadAll(): List<AgentGraphEntity>

    @Query("SELECT * FROM agent_graphs WHERE id = :id")
    suspend fun loadById(id: String): AgentGraphEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(graph: AgentGraphEntity)

    @Query("DELETE FROM agent_graphs WHERE id = :id")
    suspend fun deleteById(id: String)
}