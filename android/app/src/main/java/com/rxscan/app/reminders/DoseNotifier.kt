package com.rxscan.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rxscan.app.MainActivity
import java.time.LocalDate

const val EXTRA_DOSE_IDS = "dose_ids"
const val EXTRA_MED_NAMES = "med_names"
const val EXTRA_SLOT = "slot"
const val EXTRA_NOTIF_ID = "notif_id"
const val ACTION_TAKEN = "com.rxscan.app.TAKEN"
const val ACTION_SNOOZE = "com.rxscan.app.SNOOZE"
const val ACTION_SKIP = "com.rxscan.app.SKIP"

/**
 * Builds and posts the grouped dose notification (spec §Notification). Lock screen
 * stays discreet: publicVersion carries a count, never names (PRD notification privacy).
 * Copy is non-advisory: names what the prescription schedules, never an instruction.
 */
object DoseNotifier {

    fun notifIdFor(date: LocalDate, slot: String): Int = "$date:$slot".hashCode()

    fun slotLabel(slot: String): String = when (slot) {
        "morning" -> "Morning"
        "afternoon" -> "Afternoon"
        else -> "Night"
    }

    fun postDoseNotification(context: Context, doses: List<DoseInstance>) {
        if (doses.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return // denial: Today's banner is the recovery path
        ensureChannels(context)

        val slot = doses.first().slot
        val id = notifIdFor(doses.first().fireAt.toLocalDate(), slot)
        val names = doses.joinToString(" · ") { it.medName }
        // ponytail: platform bell icon — swap for a branded small icon with the asset pass.
        val smallIcon = android.R.drawable.ic_popup_reminder

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_DOSES)
            .setSmallIcon(smallIcon)
            .setContentTitle("${slotLabel(slot)} medicines · ${doses.size} due")
            .build()

        val doseIds = doses.map { it.doseId }.toTypedArray()
        val medNames = doses.map { it.medName }.toTypedArray()
        fun action(label: String, act: String, req: Int): NotificationCompat.Action {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(act)
                .putExtra(EXTRA_DOSE_IDS, doseIds)
                .putExtra(EXTRA_MED_NAMES, medNames)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_NOTIF_ID, id)
            return NotificationCompat.Action(
                0, label,
                PendingIntent.getBroadcast(
                    context, id * 10 + req, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        val tap = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) // cold-start routing (Task 6) lands on Today whenever a saved Rx exists

        val notification = NotificationCompat.Builder(context, CHANNEL_DOSES)
            .setSmallIcon(smallIcon)
            .setContentTitle("${slotLabel(slot)} medicines")
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(action("Taken", ACTION_TAKEN, 1))
            .addAction(action("Snooze 30m", ACTION_SNOOZE, 2))
            .addAction(action("Skip", ACTION_SKIP, 3))
            .build()
        nm.notify(id, notification)
    }

    fun postCourseEndNotice(context: Context, medNames: List<String>) {
        if (medNames.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val names = medNames.joinToString(" · ")
        val publicNotice = NotificationCompat.Builder(context, CHANNEL_COURSE)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("A course ends tomorrow")
            .build()
        nm.notify(
            ("notice:$names").hashCode(),
            NotificationCompat.Builder(context, CHANNEL_COURSE)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Course ends tomorrow")
                .setContentText("Your $names course ends tomorrow.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotice)
                .setAutoCancel(true)
                .build(),
        )
    }
}
