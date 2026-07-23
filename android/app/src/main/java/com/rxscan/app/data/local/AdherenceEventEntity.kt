package com.rxscan.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Taken/Snoozed/Skipped response (spec §Adherence). doseId is DosePlan's
 * deterministic "$rxLocalId:$medIndex:$date:$slot" — no dose table exists.
 */
@Entity(tableName = "adherence_events")
data class AdherenceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val doseId: String,
    val medName: String,
    val action: String, // "taken" | "snoozed" | "skipped"
    val at: String,     // ISO-8601 OffsetDateTime
    val pendingSync: Boolean,
)
