package com.rxscan.app.data

import android.content.Context
import androidx.room.Room
import com.rxscan.app.data.local.MIGRATION_1_2
import com.rxscan.app.data.local.PrescriptionEntity
import com.rxscan.app.data.local.RxScanDatabase
import java.time.OffsetDateTime

internal object RxScanDatabaseHolder {
    @Volatile private var instance: RxScanDatabase? = null
    fun get(context: Context): RxScanDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, RxScanDatabase::class.java, "rxscan.db")
            .addMigrations(MIGRATION_1_2)
            .build().also { instance = it }
    }
}

/**
 * Disposable local cache of the server's prescription record (spec "FE bindings +
 * store"). A confirmed prescription is saved here the moment Verify's hard gate
 * passes — rxId is null / pendingSync is true until the post-login POST/PATCH
 * assigns a server id (see SyncRepository).
 */
class PrescriptionRepository(context: Context) {
    private val dao = RxScanDatabaseHolder.get(context).prescriptionDao()

    suspend fun saveDraft(payloadJson: String): Long = dao.insert(
        PrescriptionEntity(
            rxId = null,
            payloadJson = payloadJson,
            pendingSync = true,
            updatedAt = OffsetDateTime.now().toString(),
        ),
    )

    suspend fun pendingSync(): List<PrescriptionEntity> = dao.pendingSync()

    suspend fun markSynced(localId: Long, rxId: String, updatedAt: String) {
        dao.markSynced(localId, rxId, updatedAt)
    }

    suspend fun all(): List<PrescriptionEntity> = dao.all()

    suspend fun updatePayload(localId: Long, payloadJson: String, updatedAt: String) {
        dao.updatePayload(localId, payloadJson, updatedAt)
    }
}
