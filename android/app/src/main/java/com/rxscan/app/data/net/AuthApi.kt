package com.rxscan.app.data.net

import retrofit2.http.Body
import retrofit2.http.POST

/** Unauthenticated auth endpoints (docs/api-contract-v1.md "Auth"). */
interface AuthApi {
    /** POST /v1/auth/otp/request — 200 {} on success, 422 invalid_phone. */
    @POST("v1/auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequestDto)

    /** POST /v1/auth/otp/verify — 200 {token, user_created}, 401 invalid_otp. */
    @POST("v1/auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequestDto): OtpVerifyResponseDto
}
