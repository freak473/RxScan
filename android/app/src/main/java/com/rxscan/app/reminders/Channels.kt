package com.rxscan.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

const val CHANNEL_DOSES = "dose_reminders"   // high importance → system-default sound
const val CHANNEL_COURSE = "course_updates"  // default importance — notices must not ring like doses

/** Idempotent — createNotificationChannel is a no-op for an existing id. */
fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_DOSES, "Dose reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Rings when your prescription's doses are due" },
    )
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_COURSE, "Course updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A course ending soon" },
    )
}
