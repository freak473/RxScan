package com.rxscan.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Disposable local cache of a confirmed prescription (spec "FE bindings + store").
 * rxId is null / pendingSync=true until the post-login POST/PATCH assigns a server id.
 * Deliberately unnormalized — med/dose tables arrive with the alarm+adherence slice.
 */
@Entity(tableName = "prescriptions")
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val rxId: String?,
    val payloadJson: String,
    val pendingSync: Boolean,
    val updatedAt: String,
)
