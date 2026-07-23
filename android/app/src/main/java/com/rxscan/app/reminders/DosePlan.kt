package com.rxscan.app.reminders

import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedsPayloadDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** One saved prescription, parsed. localId = Room PK; dose ids embed it. */
data class RxSource(val localId: Long, val payload: MedsPayloadDto)

data class DoseInstance(
    val doseId: String,      // "$rxLocalId:$medIndex:$date:$slot" — deterministic, never stored
    val medName: String,
    val strength: String?,
    val slot: String,        // "morning" | "afternoon" | "night"
    val mealTiming: String?, // "before_food" | "after_food" | null
    val fireAt: LocalDateTime,
)

sealed interface ReminderEvent { val fireAt: LocalDateTime }
data class DoseFire(override val fireAt: LocalDateTime, val doses: List<DoseInstance>) : ReminderEvent
data class CourseEndNotice(override val fireAt: LocalDateTime, val medNames: List<String>) : ReminderEvent

/**
 * Pure dose arithmetic (spec: reminder-plane design §Dose model). No Android deps —
 * fully JVM-testable. Day 1 = confirmedAt's date (PRD date convention). PRN never
 * schedules. durationDays == null and not PRN ⇒ ongoing, never expires.
 * AC/PC offsets: before_food = meal − 30 min, after_food = meal + 30 min.
 */
object DosePlan {
    const val DUE_SLACK_MINUTES = 90L

    // ponytail: 370-day scan horizon — covers any v1 course incl. the next dose of an
    // ongoing regimen; revisit only if year-long gaps between doses ever exist.
    private const val HORIZON_DAYS = 370L

    fun slotTime(slot: String, mealTiming: String?, meals: MealTimesDto): LocalTime {
        val meal = LocalTime.parse(
            when (slot) {
                "morning" -> meals.breakfast
                "afternoon" -> meals.lunch
                else -> meals.dinner
            },
        )
        return when (mealTiming) {
            "before_food" -> meal.minusMinutes(30)
            "after_food" -> meal.plusMinutes(30)
            else -> meal
        }
    }

    private fun day1(payload: MedsPayloadDto): LocalDate = OffsetDateTime.parse(payload.confirmedAt).toLocalDate()

    fun dosesOn(date: LocalDate, rxs: List<RxSource>, meals: MealTimesDto): List<DoseInstance> =
        rxs.flatMap { rx ->
            val start = day1(rx.payload)
            rx.payload.meds.flatMapIndexed { i, med ->
                val last = med.durationDays?.let { start.plusDays(it - 1L) }
                if (med.prn || date < start || (last != null && date > last)) {
                    emptyList()
                } else {
                    med.slots.map { slot ->
                        DoseInstance(
                            doseId = "${rx.localId}:$i:$date:$slot",
                            medName = med.name,
                            strength = med.strength,
                            slot = slot,
                            mealTiming = med.mealTiming,
                            fireAt = date.atTime(slotTime(slot, med.mealTiming, meals)),
                        )
                    }
                }
            }
        }.sortedBy { it.fireAt }

    fun todaysDoses(today: LocalDate, rxs: List<RxSource>, meals: MealTimesDto): List<DoseInstance> =
        dosesOn(today, rxs, meals)

    /** Doses whose fire time is within the last [DUE_SLACK_MINUTES] and not yet acted on. */
    fun dueAt(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto, acted: Set<String>): List<DoseInstance> {
        val from = now.minusMinutes(DUE_SLACK_MINUTES)
        return (dosesOn(now.toLocalDate().minusDays(1), rxs, meals) + dosesOn(now.toLocalDate(), rxs, meals))
            .filter { it.fireAt > from && it.fireAt <= now && it.doseId !in acted }
    }

    /** The single next thing to arm: earliest future dose fire or course-end notice. */
    fun nextEvent(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto): ReminderEvent? {
        var date = now.toLocalDate()
        val end = date.plusDays(HORIZON_DAYS)
        var nextFire: DoseFire? = null
        while (date <= end) {
            val future = dosesOn(date, rxs, meals).filter { it.fireAt > now }
            if (future.isNotEmpty()) {
                val at = future.minOf { it.fireAt }
                nextFire = DoseFire(at, future.filter { it.fireAt == at })
                break
            }
            date = date.plusDays(1)
        }
        val notice = nextCourseEndNotice(now, rxs, meals)
        return when {
            nextFire == null -> notice
            notice == null || nextFire.fireAt <= notice.fireAt -> nextFire
            else -> notice
        }
    }

    // "Your <name> course ends tomorrow": evening before the last dose day, at
    // dinner + 15 min. The +15 dodges an exact collision with a dinner-time dose
    // fire — a tie would be skipped by the chain's strictly-after semantics.
    private fun nextCourseEndNotice(now: LocalDateTime, rxs: List<RxSource>, meals: MealTimesDto): CourseEndNotice? =
        rxs.flatMap { rx ->
            val start = day1(rx.payload)
            rx.payload.meds.mapNotNull { med ->
                val days = med.durationDays
                if (days == null || med.prn) return@mapNotNull null
                val at = start.plusDays(days - 2L).atTime(LocalTime.parse(meals.dinner).plusMinutes(15))
                if (at > now) med.name to at else null
            }
        }.groupBy({ it.second }, { it.first })
            .minByOrNull { it.key }
            ?.let { CourseEndNotice(it.key, it.value) }
}
