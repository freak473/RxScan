package com.rxscan.app.data

import android.content.Context
import android.net.Uri
import com.rxscan.app.data.net.FlagDto
import com.rxscan.app.data.net.MedParseResultDto
import com.rxscan.app.data.net.Network
import com.rxscan.app.data.net.SlotsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Calls POST /extract with the captured/picked photo and maps the parsed result
 * into the app's [Medication] model that Verify renders.
 *
 * Mapping keeps the CDSCO firewall intact: displayed values are always what was
 * read, and a flag never carries a suggested value.
 */
class ExtractionRepository(private val appContext: Context) {

    /** @throws java.io.IOException on transport failure, retrofit2.HttpException on non-2xx. */
    suspend fun extract(uri: Uri): List<Medication> = withContext(Dispatchers.IO) {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Could not read the selected image")

        val part = MultipartBody.Part.createFormData(
            "image",
            "prescription.jpg",
            bytes.toRequestBody("image/jpeg".toMediaType()),
        )
        Network.extractionApi.extract(part).medicines
            .mapIndexed { i, dto -> dto.toMedication(i) }
    }
}

// ---- DTO → domain mapping -------------------------------------------------

private fun MedParseResultDto.toMedication(index: Int): Medication {
    val name = drug?.value?.ifBlank { null } ?: "Unknown medicine"
    val strengthValue = strength?.value?.ifBlank { null }
    val durationText = duration?.toDisplay()
    val scheduleText = frequency?.slots.toSchedule()
    val foodText = mealTiming?.value.toFood()
    val isPrn = frequency?.pattern?.uppercase() in setOf("PRN", "STAT")
    val flag = flags.toReCheckFlag()

    return Medication(
        id = "m${index + 1}",
        name = name,
        strength = strengthValue,
        ink = frequency?.raw?.ifBlank { null } ?: name,
        schedule = scheduleText,
        food = foodText,
        duration = durationText,
        aloud = buildAloud(name, scheduleText, foodText, durationText, isPrn, flag),
        prn = isPrn,
        flag = flag,
    )
}

private fun SlotsDto?.toSchedule(): String {
    if (this == null) return ""
    val parts = buildList {
        if (morning > 0) add("Morning")
        if (noon > 0) add("Noon")
        if (night > 0) add("Night")
        if (bedtime > 0) add("Bedtime")
    }
    return parts.joinToString(" · ")
}

private fun String?.toFood(): String = when (this?.uppercase()) {
    "BEFORE" -> "Before food"
    "AFTER" -> "After food"
    "WITH" -> "With food"
    else -> ""
}

private fun com.rxscan.app.data.net.DurationDto.toDisplay(): String? = when (type?.uppercase()) {
    "DAYS" -> days?.let { "$it days" }
    "ONGOING" -> "Ongoing"
    else -> null // UNSPECIFIED / unknown → unclear (a DURATION flag drives the re-check)
}

/**
 * Collapse the backend's flag list into the one re-check the current model holds,
 * by priority. Frequency anomalies win — a non-daily medicine misread as daily is
 * the highest-harm error and must never be hidden behind a lesser flag.
 */
private fun List<FlagDto>.toReCheckFlag(): ReCheckFlag? {
    if (isEmpty()) return null
    val reasons = mapNotNull { it.reason?.uppercase() }

    fun has(vararg r: String) = reasons.any { it in r }

    return when {
        has("FREQ_NON_DAILY", "FREQ_UNRECOGNIZED") -> ReCheckFlag(
            kind = FlagKind.OTHER,
            title = "Please check how often to take this",
            body = "This didn’t read as a simple once-a-day medicine. Type exactly what your paper says about how often — if you’re unsure, ask your doctor or pharmacist.",
            placeholder = "e.g. what the paper says",
        )
        has("STRENGTH_UNREADABLE", "STRENGTH_ANOMALY") -> ReCheckFlag(
            kind = FlagKind.STRENGTH,
            title = "We couldn’t read the strength",
            body = "Type exactly what’s written on your paper. If you can’t read it either, ask your pharmacist or doctor — we never guess doses.",
            placeholder = "e.g. what the paper says",
        )
        has("DURATION_UNCLEAR") -> ReCheckFlag(
            kind = FlagKind.DURATION,
            title = "How many days?",
            body = "We couldn’t read the number of days. Type exactly what your paper says.",
            placeholder = "days",
        )
        else -> { // NAME_LOW_CONFIDENCE, MEAL, FIELD_LOW_CONFIDENCE → generic re-check
            val field = firstOrNull()?.field?.lowercase() ?: "this"
            ReCheckFlag(
                kind = FlagKind.OTHER,
                title = "Please double-check this medicine",
                body = "We weren’t fully sure about the $field. Compare it with your paper and type what it says.",
                placeholder = "e.g. what the paper says",
            )
        }
    }
}

private fun buildAloud(
    name: String,
    schedule: String,
    food: String,
    duration: String?,
    prn: Boolean,
    flag: ReCheckFlag?,
): String {
    val parts = buildList {
        if (prn) add("only when needed")
        if (schedule.isNotBlank()) add(schedule.lowercase())
        if (food.isNotBlank()) add(food.lowercase())
        if (duration != null) add("for $duration")
    }
    val body = if (parts.isEmpty()) "" else "Your prescription says: ${parts.joinToString(", ")}."
    val check = if (flag != null) " Please check the ${flag.title.lowercase()} on your paper." else ""
    return "$name. $body$check".trim()
}
