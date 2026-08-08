package com.royna.stickersftw.network

import retrofit2.Response

/** Backend-agnostic counterpart to [Response]/[toApiErrorOrNull]: the
 * result of a [TelegramBackend] operation once its raw HTTP response has
 * been interpreted. Only covers HTTP-level (non-2xx) outcomes -- real
 * transport failures (timeouts, dropped connections) still propagate as
 * thrown [java.io.IOException]s, exactly like the raw [Response] callers
 * dealt with before this abstraction existed, so [retryTransientErrors]
 * keeps working unchanged around a [TelegramBackend] call. */
sealed class ApiResult<out T> {
    data class Success<out T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

/** Converts a completed Retrofit [Response] into an [ApiResult], reusing
 * [toApiErrorOrNull] for the failure mapping. A 2xx response with a null
 * body (shouldn't normally happen for the routes this app calls) is
 * treated as [ApiError.Unknown] rather than silently succeeding with a
 * missing value. */
fun <T> Response<T>.toApiResult(): ApiResult<T> {
    toApiErrorOrNull()?.let { return ApiResult.Failure(it) }
    val value = body() ?: return ApiResult.Failure(ApiError.Unknown(code(), "Empty response body."))
    return ApiResult.Success(value)
}

/** Same as [toApiResult] but for calls whose success case carries no
 * meaningful body (deletes) -- never inspects [Response.body], since a
 * legitimately empty 2xx body would otherwise be misread as a failure. */
fun Response<*>.toApiUnitResult(): ApiResult<Unit> {
    toApiErrorOrNull()?.let { return ApiResult.Failure(it) }
    return ApiResult.Success(Unit)
}
