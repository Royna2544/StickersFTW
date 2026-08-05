package com.royna.stickersftw.network

import com.google.gson.Gson
import com.royna.stickersftw.network.dto.ErrorBodyDto
import retrofit2.Response

sealed class ApiError(val userMessage: String) {
    data class BadRequest(val detail: String?) : ApiError(detail ?: "Invalid request.")
    data class NotFound(val detail: String?) : ApiError(detail ?: "Not found.")
    data class RateLimited(val detail: String?) : ApiError(detail ?: "Rate limited -- try again shortly.")
    data class ServerError(val detail: String?) : ApiError(detail ?: "Server error.")
    data class Unknown(val code: Int, val detail: String?) :
        ApiError(detail ?: "Unexpected error ($code).")
    data class Network(val cause: Throwable) : ApiError(cause.message ?: "Network error.")
}

private val errorBodyGson = Gson()

/** Maps a non-2xx [Response] to a typed [ApiError], parsing the server's
 * uniform JSON error body when present and falling back gracefully (a
 * generic message for that status code) if the body is empty or malformed --
 * e.g. a transport-level failure that never reached the server's handlers. */
fun <T> Response<T>.toApiErrorOrNull(): ApiError? {
    if (isSuccessful) return null

    val bodyText = try {
        errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    val detail = bodyText?.let {
        try {
            errorBodyGson.fromJson(it, ErrorBodyDto::class.java)?.description
        } catch (_: Exception) {
            null
        }
    }

    return when (code()) {
        400 -> ApiError.BadRequest(detail)
        404 -> ApiError.NotFound(detail)
        429 -> ApiError.RateLimited(detail)
        500 -> ApiError.ServerError(detail)
        else -> ApiError.Unknown(code(), detail)
    }
}
