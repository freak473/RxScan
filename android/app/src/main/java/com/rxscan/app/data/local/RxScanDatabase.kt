package com.rxscan.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

// exportSchema=false: a single-entity dev-phase cache, no versioned migrations yet.
// Add schema export + real Migration objects when the alarm/adherence slice needs them.
@Database(entities = [PrescriptionEntity::class], version = 1, exportSchema = false)
abstract class RxScanDatabase : RoomDatabase() {
    abstract fun prescriptionDao(): PrescriptionDao
}
