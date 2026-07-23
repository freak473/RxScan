package com.rxscan.app.data

import android.content.Context
import com.rxscan.app.data.local.AdherenceEventEntity
import java.time.OffsetDateTime

/** Device-first adherence log (spec §Adherence); synced inside the opaque payload. */
class AdherenceRepository(context: Context) {
    private val dao = RxScanDatabaseHolder.get(context).adherenceDao()

    suspend fun record(doseIds: List<String>, medNames: List<String>, action: String) {
        val at = OffsetDateTime.now().toString()
        doseIds.forEachIndexed { i, id ->
            dao.insert(
                AdherenceEventEntity(
                    doseId = id,
                    medName = medNames.getOrElse(i) { "" },
                    action = action,
                    at = at,
                    pendingSync = true,
                ),
            )
        }
    }

    suspend fun all(): List<AdherenceEventEntity> = dao.all()
    suspend fun pending(): List<AdherenceEventEntity> = dao.pending()
    suspend fun markSynced(ids: List<Long>) = dao.markSynced(ids)
}
