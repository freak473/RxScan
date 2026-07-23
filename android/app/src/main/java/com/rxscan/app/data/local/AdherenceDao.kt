package com.rxscan.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AdherenceDao {
    @Insert
    suspend fun insert(event: AdherenceEventEntity)

    @Query("SELECT * FROM adherence_events")
    suspend fun all(): List<AdherenceEventEntity>

    @Query("SELECT * FROM adherence_events WHERE pendingSync = 1")
    suspend fun pending(): List<AdherenceEventEntity>

    @Query("UPDATE adherence_events SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}
