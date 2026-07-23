package com.rxscan.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.rxscan.app.data.AdherenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Runs [block] off the main thread inside the receiver's goAsync window. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** The chain alarm (dose fire / course-end notice) and snooze re-posts land here. */
class DoseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            ReminderScheduler.ACTION_NOTICE_FIRE -> {
                DoseNotifier.postCourseEndNotice(
                    context, intent.getStringArrayExtra(EXTRA_MED_NAMES)?.toList() ?: emptyList(),
                )
                ReminderScheduler.reschedule(context)
            }
            ReminderScheduler.ACTION_SNOOZE_FIRE -> {
                // Snooze re-post rebuilds from extras — short-lived, staleness acceptable (spec).
                val ids = intent.getStringArrayExtra(EXTRA_DOSE_IDS) ?: emptyArray()
                val names = intent.getStringArrayExtra(EXTRA_MED_NAMES) ?: emptyArray()
                val slot = intent.getStringExtra(EXTRA_SLOT) ?: "morning"
                val now = LocalDateTime.now()
                DoseNotifier.postDoseNotification(
                    context,
                    ids.mapIndexed { i, id -> DoseInstance(id, names.getOrElse(i) { "" }, null, slot, null, now) },
                )
                // No reschedule: the snooze alarm is a one-off outside the chain.
            }
            else -> { // ACTION_DOSE_FIRE — never trust stale extras: recompute what's due now
                val sources = ReminderScheduler.loadSources(context)
                if (sources != null) {
                    val acted = AdherenceRepository(context).all().map { it.doseId }.toSet()
                    DoseNotifier.postDoseNotification(
                        context, DosePlan.dueAt(LocalDateTime.now(), sources.first, sources.second, acted),
                    )
                }
                ReminderScheduler.reschedule(context)
            }
        }
    }
}

/** Taken / Snooze 30m / Skip from the notification — group-level (all doses in it). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_TAKEN -> "taken"
            ACTION_SNOOZE -> "snoozed"
            ACTION_SKIP -> "skipped"
            else -> return
        }
        val doseIds = intent.getStringArrayExtra(EXTRA_DOSE_IDS) ?: return
        val medNames = intent.getStringArrayExtra(EXTRA_MED_NAMES) ?: emptyArray()
        val slot = intent.getStringExtra(EXTRA_SLOT) ?: "morning"
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        NotificationManagerCompat.from(context).cancel(notifId)
        async {
            AdherenceRepository(context).record(doseIds.toList(), medNames.toList(), action)
            if (action == "snoozed") ReminderScheduler.armSnooze(context, doseIds, medNames, slot, notifId)
        }
    }
}

/** Re-arm the chain after reboot or clock/zone changes. All three are protected broadcasts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        ReminderScheduler.reschedule(context)
    }
}
