package com.rxscan.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// exportSchema=false: dev-phase cache; MIGRATION_1_2 below is the first real migration
// (v1 installs exist on dev devices — don't wipe their saved prescriptions).
@Database(entities = [PrescriptionEntity::class, AdherenceEventEntity::class], version = 2, exportSchema = false)
abstract class RxScanDatabase : RoomDatabase() {
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun adherenceDao(): AdherenceDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `adherence_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`doseId` TEXT NOT NULL, `medName` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, `at` TEXT NOT NULL, " +
                "`pendingSync` INTEGER NOT NULL)",
        )
    }
}
