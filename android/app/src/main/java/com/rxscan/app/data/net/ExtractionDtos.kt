package com.rxscan.app.data.net

// Gson DTOs mirroring the backend POST /extract response (ExtractionResponse →
// MedParseResult, tech-design §4). Enums are kept as String so an unknown value
// from a newer backend degrades gracefully instead of throwing.

data class ExtractionResponseDto(
    val medicines: List<MedParseResultDto> = emptyList(),
)

data class MedParseResultDto(
    val drug: DrugDto?,
    val strength: StrengthDto?,
    val frequency: FrequencyDto?,
    val mealTiming: MealTimingDto?,
    val duration: DurationDto?,
    val flags: List<FlagDto> = emptyList(),
)

data class DrugDto(val value: String?, val formularyId: Long?, val confidence: Double = 0.0)

data class StrengthDto(val value: String?, val confidence: Double = 0.0)

data class FrequencyDto(
    val raw: String?,
    val slots: SlotsDto?,
    val pattern: String?, // DAILY | WEEKLY | ALTERNATE_DAY | PRN | STAT
    val confidence: Double = 0.0,
)

data class SlotsDto(
    val morning: Double = 0.0,
    val noon: Double = 0.0,
    val night: Double = 0.0,
    val bedtime: Double = 0.0,
)

data class MealTimingDto(val value: String?, val confidence: Double = 0.0) // BEFORE | AFTER | WITH | null

data class DurationDto(
    val type: String?, // DAYS | ONGOING | UNSPECIFIED
    val days: Int?,
    val confidence: Double = 0.0,
)

data class FlagDto(
    val field: String?, // DRUG | STRENGTH | FREQUENCY | MEAL | DURATION
    val reason: String?, // FREQ_NON_DAILY | STRENGTH_UNREADABLE | DURATION_UNCLEAR | ...
)
