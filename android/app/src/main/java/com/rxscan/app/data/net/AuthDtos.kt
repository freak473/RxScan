package com.rxscan.app.data.net

import com.google.gson.annotations.SerializedName

// Auth + consent wire DTOs (docs/api-contract-v1.md "Auth", "Me"). snake_case on the wire
// via @SerializedName on the backend's own envelope fields only — the FE-owned payload
// schemas stay camelCase (see PrescriptionDtos.kt / MeDtos.kt).

data class OtpRequestDto(val phone: String)

data class ConsentDto(
    val purpose: String, // "process" | "notify" | "retain_optin"
    val granted: Boolean,
    @SerializedName("granted_at") val grantedAt: String, // ISO8601, device-side grant time
)

data class OtpVerifyRequestDto(
    val phone: String,
    val otp: String,
    val consents: List<ConsentDto>,
)

data class OtpVerifyResponseDto(
    val token: String,
    @SerializedName("user_created") val userCreated: Boolean,
)

data class ConsentsPutRequestDto(val consents: List<ConsentDto>)
