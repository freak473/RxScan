package com.rxscan.app.data.net

import com.google.gson.Gson
import retrofit2.HttpException

// Uniform error envelope (docs/api-contract-v1.md "Contract rules"): {"error":{code,message}}.

data class ApiErrorBodyDto(val code: String, val message: String)
data class ApiErrorEnvelopeDto(val error: ApiErrorBodyDto)

/** A parsed backend error: HTTP status + machine code + human message. */
class ApiException(val httpStatus: Int, val code: String, message: String) : Exception(message)

object ApiErrors {
    private val gson = Gson()

    /** Parses the uniform {"error":{code,message}} envelope off a failed Retrofit call. */
    fun from(e: HttpException): ApiException {
        val raw = e.response()?.errorBody()?.string()
        val parsed = raw?.let { runCatching { gson.fromJson(it, ApiErrorEnvelopeDto::class.java) }.getOrNull() }
        return ApiException(
            httpStatus = e.code(),
            code = parsed?.error?.code ?: "unknown",
            message = parsed?.error?.message ?: (e.message() ?: "Request failed"),
        )
    }
}
