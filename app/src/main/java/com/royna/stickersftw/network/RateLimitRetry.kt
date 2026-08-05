package com.royna.stickersftw.network

import kotlin.random.Random
import kotlinx.coroutines.delay
import retrofit2.Response

/** The server never sends a Retry-After header on 429s (it can't -- Telegram
 * doesn't give it one either), so callers back off blindly with jittered
 * exponential delay instead of trusting a header that won't exist. */
suspend fun <T> withRateLimitRetry(
    maxAttempts: Int = 4,
    block: suspend () -> Response<T>,
): Response<T> {
    var attempt = 0
    var delayMs = 500L
    while (true) {
        val response = block()
        if (response.code() != 429 || attempt >= maxAttempts - 1) {
            return response
        }
        delay(delayMs + Random.nextLong(0, 250))
        delayMs *= 2
        attempt++
    }
}
