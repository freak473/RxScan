package com.rxscan.app.data.net

import okhttp3.Interceptor
import okhttp3.Response

// Adds Bearer token to /v1/me/** and /v1/prescriptions/** routes only.
// /extract and /v1/auth/** stay open. Reads TokenCache for the JWT.
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val needsAuth = path.startsWith("/v1/me/") || path.startsWith("/v1/prescriptions")
        val token = TokenCache.current
        val authed = if (needsAuth && token != null) {
            request.newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return chain.proceed(authed)
    }
}

// Synchronous mirror of the JWT, updated by RxScanStore (Task 3).
// DataStore is Flow-based; OkHttp interceptors run on plain threads.
object TokenCache {
    @Volatile var current: String? = null
}
