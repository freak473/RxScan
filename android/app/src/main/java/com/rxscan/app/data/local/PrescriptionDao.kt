package com.rxscan.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PrescriptionDao {
    @Insert
    suspend fun insert(entity: PrescriptionEntity): Long

    @Query("SELECT * FROM prescriptions WHERE pendingSync = 1")
    suspend fun pendingSync(): List<PrescriptionEntity>

    @Query("UPDATE prescriptions SET rxId = :rxId, pendingSync = 0, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun markSynced(localId: Long, rxId: String, updatedAt: String)
}
