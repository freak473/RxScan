package com.rxscan.app.data.net

import com.rxscan.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Base URL for the backend. The Android emulator reaches the host machine's
 * loopback via the special alias 10.0.2.2 (localhost = the emulator itself).
 * Change this to the host's LAN IP when running on a physical device.
 */
object ApiConfig {
    const val BASE_URL = "http://10.0.2.2:8080/"
}

/** Lazily-built Retrofit + OkHttp stack. Vision reads can be slow, so timeouts are generous. */
object Network {

    val extractionApi: ExtractionApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExtractionApi::class.java)
    }
}
