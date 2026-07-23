package com.rxscan.app.data.net

import com.google.gson.Gson
import com.rxscan.app.data.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadMapperTest {

    @Test
    fun `confirmed meds map to the wire schema exactly`() {
        val meds = listOf(
            Medication(
                id = "m1", name = "Augmentin 625 Duo", strength = "625 mg",
                ink = "x", schedule = "Morning · Night", food = "After food",
                duration = "5 days · ends Wed, 15 Jul", aloud = "x",
            ),
            Medication(
                id = "m4", name = "Dolo 650", strength = "650 mg",
                ink = "x", schedule = "When needed (SOS)", food = "As written",
                duration = "No fixed course", aloud = "x", prn = true,
            ),
        )

        val payload = meds.toMedsPayload("2026-07-23T10:30:00+05:30")

        assertEquals(1, payload.schema)
        assertEquals("2026-07-23T10:30:00+05:30", payload.confirmedAt)
        assertEquals(2, payload.meds.size)

        val augmentin = payload.meds[0]
        assertEquals("Augmentin 625 Duo", augmentin.name)
        assertEquals("625 mg", augmentin.strength)
        assertEquals(listOf("morning", "night"), augmentin.slots)
        assertEquals("after_food", augmentin.mealTiming)
        assertEquals(5, augmentin.durationDays)
        assertFalse(augmentin.prn)

        val dolo = payload.meds[1]
        assertEquals(emptyList<String>(), dolo.slots)
        assertNull(dolo.mealTiming)
        assertNull(dolo.durationDays) // PRN never carries a duration
        assertEquals(true, dolo.prn)
    }

    @Test
    fun `gson serializes the wire payload without a suggested_value field`() {
        val payload = MedsPayloadDto(
            schema = 1,
            meds = listOf(
                MedItemDto(
                    name = "Dolo 650", strength = "650 mg", slots = listOf("morning"),
                    mealTiming = "after_food", durationDays = 5, prn = false,
                ),
            ),
            confirmedAt = "2026-07-23T10:30:00+05:30",
        )
        val json = Gson().toJson(payload)
        assertFalse(json.contains("suggested_value")) // CDSCO: never present
        assertEquals(true, json.contains("\"confirmedAt\""))
    }
}
