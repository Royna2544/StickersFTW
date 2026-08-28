package com.royna.stickersftw.conversion

import kotlin.math.ceil

/** Chooses uniformly distributed frame timestamps without depending on an
 * Android decoder. Capping the number of frames changes the sampling interval,
 * not the represented end time, so a capped animation is not shortened. */
object FrameSamplingPolicy {
    fun sampleTimestampsMs(
        durationMs: Long,
        sourceFps: Double,
        maxFrames: Int,
        minFrameDurationMs: Long,
    ): List<Long> {
        if (
            durationMs <= 0L ||
            !sourceFps.isFinite() ||
            sourceFps <= 0.0 ||
            maxFrames <= 0 ||
            minFrameDurationMs <= 0L
        ) {
            return emptyList()
        }

        val sourceFrameCount = ceil(durationMs.toDouble() * sourceFps / 1000.0)
            .coerceIn(1.0, Int.MAX_VALUE.toDouble())
            .toInt()
        val maxFramesByDuration = (durationMs / minFrameDurationMs)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        val outputFrameCount = minOf(sourceFrameCount, maxFrames, maxFramesByDuration)

        val count = outputFrameCount.toLong()
        val wholeMs = durationMs / count
        val remainderMs = durationMs % count
        return List(outputFrameCount) { index ->
            val position = index.toLong()
            position * wholeMs + position * remainderMs / count
        }
    }

    /** A representative positive frame gap. Median resists an omitted decoder
     * output: one missing 33ms frame creates a single 66ms gap but must not
     * make the animation hold every final frame for 66ms. */
    fun estimateIntervalMs(timestampsMs: List<Long>, fallbackMs: Long = 100L): Long {
        val positiveDeltas = timestampsMs.zipWithNext { first, second -> second - first }
            .filter { it > 0L }
            .sorted()
        // Prefer the lower middle value for an even number of samples. With
        // [0, 66, 99], for example, one dropped 33ms output yields [33, 66];
        // the normal cadence is still 33ms, not 66ms.
        return positiveDeltas.getOrNull((positiveDeltas.size - 1) / 2)
            ?: fallbackMs.coerceAtLeast(1L)
    }

    /** Timestamp for a duplicate final hold when decoder outputs at either
     * edge are missing. Interior gaps already hold the preceding frame until
     * the next timestamp; a missing tail needs an explicit marker, while a
     * missing head needs the same duration restored after rebasing to zero. */
    fun trailingHoldTimestampMs(
        wantedTimestampsMs: List<Long>,
        decodedTimestampsMs: List<Long>,
    ): Long? {
        // One real decoder output is a static result. Duplicating it here
        // would make callers label it animated even though WebPAnimEncoder
        // coalesces identical frames back into a still image.
        if (decodedTimestampsMs.size < 2) return null
        val wantedFirst = wantedTimestampsMs.firstOrNull() ?: return null
        val wantedLast = wantedTimestampsMs.last()
        val decodedFirst = decodedTimestampsMs.firstOrNull() ?: return null
        val decodedLast = decodedTimestampsMs.last()
        val intendedLast = wantedLast + (decodedFirst - wantedFirst).coerceAtLeast(0L)
        return intendedLast.takeIf { it > decodedLast }
    }
}
