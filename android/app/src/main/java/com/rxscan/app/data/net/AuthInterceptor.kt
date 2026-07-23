package com.rxscan.app.data.net

import okhttp3.Interceptor
import okhttp3.Response

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

object TokenCache {
    @Volatile var current: String? = null
}
