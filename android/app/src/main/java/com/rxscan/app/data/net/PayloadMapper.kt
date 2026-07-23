package com.rxscan.app.data.net

import com.rxscan.app.data.Medication

/**
 * Confirmed meds (data/Model.kt) → the FE-owned meds payload the backend stores
 * opaquely (docs/api-contract-v1.md). CDSCO: there is no suggested_value field here —
 * every value written is what the user confirmed on the Verify screen.
 */
fun List<Medication>.toMedsPayload(confirmedAt: String): MedsPayloadDto = MedsPayloadDto(
    schema = 1,
    meds = map { it.toMedItemDto() },
    confirmedAt = confirmedAt,
)

private fun Medication.toMedItemDto(): MedItemDto = MedItemDto(
    name = name,
    strength = strength,
    slots = schedule.toSlots(),
    mealTiming = food.toMealTiming(),
    durationDays = if (prn) null else duration?.toDurationDays(),
    prn = prn,
)

// "Morning · Noon · Night · Bedtime" (display text, data/ExtractionRepository.kt's
// toSchedule()) → wire slots. The wire schema (api-contract-v1.md) only defines
// morning/afternoon/night; ponytail: a Bedtime-only slot has no wire equivalent yet,
// so it's dropped here — add a "bedtime" wire slot if/when the backend schema grows one.
private fun String.toSlots(): List<String> = buildList {
    val text = lowercase()
    if ("morning" in text) add("morning")
    if ("noon" in text || "afternoon" in text) add("afternoon")
    if ("night" in text) add("night")
}

private fun String.toMealTiming(): String? = when {
    "before" in lowercase() -> "before_food"
    "after" in lowercase() -> "after_food"
    else -> null
}

// e.g. "5 days · ends Wed, 15 Jul" or a free-typed "10 days" → leading integer;
// "No fixed course" / anything without a leading number → null (PRN, ongoing courses).
private fun String.toDurationDays(): Int? = Regex("^(\\d+)").find(trim())?.groupValues?.get(1)?.toIntOrNull()
