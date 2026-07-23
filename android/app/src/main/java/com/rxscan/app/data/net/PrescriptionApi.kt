package com.rxscan.app.data.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Bearer-authed prescription sync (docs/api-contract-v1.md "Prescriptions"). */
interface PrescriptionApi {
    /** POST /v1/prescriptions — 201 {rx_id, updated_at}. Fires right after OTP verify succeeds. */
    @POST("v1/prescriptions")
    suspend fun create(@Body body: PrescriptionsPostRequestDto): PrescriptionsPostResponseDto

    /** PATCH /v1/prescriptions/{rxId} — 200 {updated_at}, 404 (also for someone else's rxId). */
    @PATCH("v1/prescriptions/{rxId}")
    suspend fun update(@Path("rxId") rxId: String, @Body body: PrescriptionsPostRequestDto): PrescriptionsPatchResponseDto

    /** GET /v1/prescriptions?since= — 200 {prescriptions:[...]}. since absent ⇒ full pull. */
    @GET("v1/prescriptions")
    suspend fun list(@Query("since") since: String? = null): PrescriptionsGetResponseDto
}
