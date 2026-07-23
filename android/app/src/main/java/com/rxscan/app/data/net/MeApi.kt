package com.rxscan.app.data.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/** Bearer-authed consent + preference endpoints (docs/api-contract-v1.md "Me"). */
interface MeApi {
    /** PUT /v1/me/consents — 204. Append-only rows; withdrawal = a new granted:false row. */
    @PUT("v1/me/consents")
    suspend fun putConsents(@Body body: ConsentsPutRequestDto)

    /** PUT /v1/me/preferences — 204. Upsert; exactly one row per user. */
    @PUT("v1/me/preferences")
    suspend fun putPreferences(@Body body: PreferencesPutRequestDto)

    /** GET /v1/me/preferences — 200 {payload, updated_at}, 404 if never set. */
    @GET("v1/me/preferences")
    suspend fun getPreferences(): PreferencesGetResponseDto
}
