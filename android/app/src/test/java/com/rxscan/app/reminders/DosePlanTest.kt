package com.rxscan.app.reminders

import com.rxscan.app.data.net.MealTimesDto
import com.rxscan.app.data.net.MedItemDto
import com.rxscan.app.data.net.MedsPayloadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private val MEALS = MealTimesDto(breakfast = "08:00", lunch = "13:30", dinner = "20:30")

private fun med(
    name: String = "Amox",
    strength: String? = "500 mg",
    slots: List<String> = listOf("morning"),
    mealTiming: String? = "after_food",
    durationDays: Int? = 5,
    prn: Boolean = false,
) = MedItemDto(name, strength, slots, mealTiming, durationDays, prn)

private fun rx(
    vararg meds: MedItemDto,
    confirmedAt: String = "2026-07-23T10:00:00+05:30",
    localId: Long = 1,
) = RxSource(localId, MedsPayloadDto(schema = 1, meds = meds.toList(), confirmedAt = confirmedAt))

class DosePlanTest {

    @Test fun `slot time applies AC PC offsets`() {
        assertEquals(LocalTime.of(8, 30), DosePlan.slotTime("morning", "after_food", MEALS))
        assertEquals(LocalTime.of(7, 30), DosePlan.slotTime("morning", "before_food", MEALS))
        assertEquals(LocalTime.of(8, 0), DosePlan.slotTime("morning", null, MEALS))
        assertEquals(LocalTime.of(14, 0), DosePlan.slotTime("afternoon", "after_food", MEALS))
        assertEquals(LocalTime.of(20, 0), DosePlan.slotTime("night", "before_food", MEALS))
    }

    @Test fun `prn meds are never scheduled`() {
        val doses = DosePlan.dosesOn(LocalDate.of(2026, 7, 23), listOf(rx(med(prn = true))), MEALS)
        assertTrue(doses.isEmpty())
    }

    @Test fun `course window is day1 through day1 plus duration minus 1`() {
        val rxs = listOf(rx(med(durationDays = 5))) // day1 = 2026-07-23 → last = 2026-07-27
        assertTrue(DosePlan.dosesOn(LocalDate.of(2026, 7, 22), rxs, MEALS).isEmpty())
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2026, 7, 23), rxs, MEALS).size)
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2026, 7, 27), rxs, MEALS).size)
        assertTrue(DosePlan.dosesOn(LocalDate.of(2026, 7, 28), rxs, MEALS).isEmpty())
    }

    @Test fun `ongoing course never expires`() {
        val rxs = listOf(rx(med(durationDays = null)))
        assertEquals(1, DosePlan.dosesOn(LocalDate.of(2027, 7, 22), rxs, MEALS).size)
    }

    @Test fun `dose id is deterministic`() {
        val dose = DosePlan.dosesOn(LocalDate.of(2026, 7, 23), listOf(rx(med())), MEALS).single()
        assertEquals("1:0:2026-07-23:morning", dose.doseId)
    }

    @Test fun `nextEvent groups doses firing at the same instant across meds`() {
        val rxs = listOf(rx(med(name = "A"), med(name = "B"))) // both morning after_food 08:30
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 8, 0), rxs, MEALS) as DoseFire
        assertEquals(LocalDateTime.of(2026, 7, 23, 8, 30), event.fireAt)
        assertEquals(listOf("A", "B"), event.doses.map { it.medName })
    }

    @Test fun `nextEvent is strictly after now`() {
        val rxs = listOf(rx(med(durationDays = 1)))
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 8, 30), rxs, MEALS)
        assertNull(event) // 08:30 dose not "next" at exactly 08:30; duration-1 course has nothing after
    }

    @Test fun `course auto stops - no event after last dose`() {
        val rxs = listOf(rx(med(durationDays = 2)))
        assertNull(DosePlan.nextEvent(LocalDateTime.of(2026, 7, 24, 21, 0), rxs, MEALS))
    }

    @Test fun `dueAt honours slack window and acted set`() {
        val rxs = listOf(rx(med())) // fires 08:30
        val now = LocalDateTime.of(2026, 7, 23, 9, 45) // 75 min after
        assertEquals(1, DosePlan.dueAt(now, rxs, MEALS, emptySet()).size)
        assertTrue(DosePlan.dueAt(now, rxs, MEALS, setOf("1:0:2026-07-23:morning")).isEmpty())
        val late = LocalDateTime.of(2026, 7, 23, 10, 1) // 91 min after
        assertTrue(DosePlan.dueAt(late, rxs, MEALS, emptySet()).isEmpty())
    }

    @Test fun `dueAt spans midnight`() {
        val rxs = listOf(rx(med(slots = listOf("night"), mealTiming = null)))
        val meals = MealTimesDto(breakfast = "08:00", lunch = "13:30", dinner = "23:50")
        val now = LocalDateTime.of(2026, 7, 24, 0, 30)
        assertEquals(1, DosePlan.dueAt(now, rxs, meals, emptySet()).size)
    }

    @Test fun `course end notice fires evening before last day and loses ties to earlier dose fires`() {
        val rxs = listOf(rx(med(durationDays = 5))) // last day 07-27 → notice 07-26 at 20:45
        val notice = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 26, 20, 44), rxs, MEALS)
        // At 20:44 on the 26th the day's 08:30 dose already fired; the notice at 20:45
        // is earlier than tomorrow's dose, so it wins.
        assertTrue(notice is CourseEndNotice)
        assertEquals(LocalDateTime.of(2026, 7, 26, 20, 45), (notice as CourseEndNotice).fireAt)
        assertEquals(listOf("Amox"), notice.medNames)
        // Earlier the same day, the 08:30 dose wins over the evening notice:
        val morning = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 26, 8, 0), rxs, MEALS)
        assertTrue(morning is DoseFire)
    }

    @Test fun `duration one course never emits a past notice`() {
        val rxs = listOf(rx(med(durationDays = 1)))
        val event = DosePlan.nextEvent(LocalDateTime.of(2026, 7, 23, 7, 0), rxs, MEALS)
        assertTrue(event is DoseFire) // notice day would be 07-22 (past) — filtered, dose still fires
    }
}
