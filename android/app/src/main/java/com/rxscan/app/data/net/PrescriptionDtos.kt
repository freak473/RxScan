package com.rxscan.app.data.net

import com.google.gson.annotations.SerializedName

// Prescription wire DTOs (docs/api-contract-v1.md "Prescriptions"). No suggested_value
// field exists anywhere here, by design (CDSCO: flag, don't correct).

data class MedItemDto(
    val name: String,
    val strength: String?,
    val slots: List<String>, // "morning" | "afternoon" | "night"
    val mealTiming: String?, // "before_food" | "after_food" | null
    val durationDays: Int?,
    val prn: Boolean,
)

data class MedsPayloadDto(
    val schema: Int = 1,
    val meds: List<MedItemDto>,
    val confirmedAt: String, // ISO8601
)

data class PrescriptionsPostRequestDto(val payload: MedsPayloadDto)

data class PrescriptionsPostResponseDto(
    @SerializedName("rx_id") val rxId: String,
    @SerializedName("updated_at") val updatedAt: String,
)

data class PrescriptionsPatchResponseDto(
    @SerializedName("updated_at") val updatedAt: String,
)

data class PrescriptionRecordDto(
    @SerializedName("rx_id") val rxId: String,
    val payload: MedsPayloadDto,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
)

data class PrescriptionsGetResponseDto(val prescriptions: List<PrescriptionRecordDto>)
