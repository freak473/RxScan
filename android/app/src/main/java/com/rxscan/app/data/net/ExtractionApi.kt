package com.rxscan.app.data.net

import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * The RxScan backend surface used by the app. Base URL is [ApiConfig.BASE_URL].
 * The emulator reaches the host Mac's local backend via the 10.0.2.2 alias.
 */
interface ExtractionApi {

    /** POST /extract — one prescription photo as multipart part "image" → parsed medicines. */
    @Multipart
    @POST("extract")
    suspend fun extract(@Part image: MultipartBody.Part): ExtractionResponseDto
}
