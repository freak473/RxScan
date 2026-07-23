package com.rxscan.app.data.net

import com.google.gson.annotations.SerializedName

// Preferences wire DTOs (docs/api-contract-v1.md "PUT/GET /v1/me/preferences"). The
// payload is FE-owned and server-opaque — additive-only, ours to grow.

data class MealTimesDto(
    val breakfast: String, // "HH:mm"
    val lunch: String,
    val dinner: String,
)

data class PreferencesPayloadDto(
    val schema: Int = 1,
    val mealTimes: MealTimesDto,
)

data class PreferencesPutRequestDto(val payload: PreferencesPayloadDto)

data class PreferencesGetResponseDto(
    val payload: PreferencesPayloadDto,
    @SerializedName("updated_at") val updatedAt: String,
)
