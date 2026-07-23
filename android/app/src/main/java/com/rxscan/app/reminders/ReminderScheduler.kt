package com.rxscan.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.rxscan.app.data.PrescriptionRepository
import com.rxscan.app.data.local.RxScanStore
import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedsPayloadDto
import com.rxscan.app.data.net.PreferencesPayloadDto
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Arms ONE alarm for the next reminder event — the chained next-dose design
 * (spec §Scheduling). Call it from anywhere, anytime; it recomputes and re-arms.
 * ponytail: force-stop pauses the chain until next app-open/boot — every reminder
 * app shares this; the deferred battery-exemption prompt is the upgrade path.
 */
object ReminderScheduler {
    const val ACTION_DOSE_FIRE = "com.rxscan.app.DOSE_FIRE"
    const val ACTION_NOTICE_FIRE = "com.rxscan.app.NOTICE_FIRE"
    const val ACTION_SNOOZE_FIRE = "com.rxscan.app.SNOOZE_FIRE"

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Everything DosePlan needs, loaded. Null until meal times exist (pre-mealtimes screens). */
    suspend fun loadSources(context: Context): Pair<List<RxSource>, MealTimesDto>? {
        val gson = Gson()
        val meals = RxScanStore(context).loadMealTimesJson()
            ?.let { gson.fromJson(it, PreferencesPayloadDto::class.java).mealTimes } ?: return null
        val rxs = PrescriptionRepository(context).all().map {
            RxSource(it.localId, gson.fromJson(it.payloadJson, MedsPayloadDto::class.java))
        }
        return rxs to meals
    }

    suspend fun reschedule(context: Context) {
        val (rxs, meals) = loadSources(context) ?: return
        val event = DosePlan.nextEvent(LocalDateTime.now(), rxs, meals)
        val am = context.getSystemService(AlarmManager::class.java)

        fun chainPi(action: String, fill: Intent.() -> Intent = { this }): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DoseAlarmReceiver::class.java).setAction(action).fill(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // One chain alarm total: cancel both variants before arming (they differ by action,
        // so they are distinct PendingIntents).
        am.cancel(chainPi(ACTION_DOSE_FIRE))
        am.cancel(chainPi(ACTION_NOTICE_FIRE))
        if (event == null) return // course over — auto-stop, nothing armed

        val pi = when (event) {
            is CourseEndNotice -> chainPi(ACTION_NOTICE_FIRE) {
                putExtra(EXTRA_MED_NAMES, event.medNames.toTypedArray())
            }
            is DoseFire -> chainPi(ACTION_DOSE_FIRE)
        }
        arm(am, context, event.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun armSnooze(context: Context, doseIds: Array<String>, medNames: Array<String>, slot: String, notifId: Int) {
        val intent = Intent(context, DoseAlarmReceiver::class.java)
            .setAction(ACTION_SNOOZE_FIRE)
            .putExtra(EXTRA_DOSE_IDS, doseIds)
            .putExtra(EXTRA_MED_NAMES, medNames)
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_NOTIF_ID, notifId)
        val pi = PendingIntent.getBroadcast(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        arm(context.getSystemService(AlarmManager::class.java), context, System.currentTimeMillis() + 30 * 60_000L, pi)
    }

    private fun arm(am: AlarmManager, context: Context, atMillis: Long, pi: PendingIntent) {
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Degraded fallback (spec: user-approved deviation from the PRD's WorkManager
            // windows): same chain, inexact delivery — Today shows the accuracy note.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}
