package com.royna.stickersftw.network

import java.io.IOException
import kotlinx.coroutines.delay

/** Retries [block] up to [maxAttempts] times, [delayMillis] apart, when it
 * throws a transient [IOException] (timeouts, dropped connections -- the
 * kind that often clear up moments later). [onRetry] fires before each
 * retry so a caller can surface the attempt (e.g. update a background
 * notification). Rethrows the last exception once attempts run out, so a
 * genuinely unreachable server still fails instead of retrying forever. */
suspend fun <T> retryTransientErrors(
    maxAttempts: Int = 5,
    delayMillis: Long = 1000,
    onRetry: suspend (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: IOException) {
            if (attempt >= maxAttempts) throw e
            onRetry(attempt, maxAttempts)
            delay(delayMillis)
            attempt++
        }
    }
}
