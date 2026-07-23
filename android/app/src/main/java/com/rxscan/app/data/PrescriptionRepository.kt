package com.rxscan.app.data

import android.content.Context
import androidx.room.Room
import com.rxscan.app.data.local.PrescriptionEntity
import com.rxscan.app.data.local.RxScanDatabase
import java.time.OffsetDateTime

private object RxScanDatabaseHolder {
    @Volatile private var instance: RxScanDatabase? = null
    fun get(context: Context): RxScanDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, RxScanDatabase::class.java, "rxscan.db")
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
}
