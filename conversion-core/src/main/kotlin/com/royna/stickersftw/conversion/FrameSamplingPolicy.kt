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

    /** Playback duration represented by a complete sampled timeline. */
    fun durationMs(timestampsMs: List<Long>, fallbackMs: Long = 100L): Long? {
        val first = timestampsMs.firstOrNull() ?: return null
        val last = timestampsMs.last()
        return (last - first + estimateIntervalMs(timestampsMs, fallbackMs)).coerceAtLeast(1L)
    }

    /** Absolute final timestamp passed to an animation encoder.
     *
     * [durationHintMs] comes from the complete pre-decode/pre-decimation
     * timeline. Holding the last retained frame to that fixed boundary keeps
     * `[0, 99]` from turning a 132ms `0/33/66/99` animation into 198ms, and
     * keeps later size-budget frame cuts from changing playback speed.
     *
     * The floor is a whole frame, not a millisecond. The encoder derives the
     * final frame's duration from the gap between it and this endpoint, so a
     * `last + 1` floor produces a legal-looking one-millisecond frame -- above
     * zero, and still below the minimum WhatsApp accepts, which rejects the
     * pack it belongs to. A hint that lands at or before the last retained
     * timestamp is exactly when that floor takes over: six of one real
     * 27-sticker pack's stickers ended on a 1ms frame that way. */
    fun endTimestampMs(
        retainedTimestampsMs: List<Long>,
        durationHintMs: Long?,
        fallbackMs: Long = 100L,
    ): Long? {
        val first = retainedTimestampsMs.firstOrNull() ?: return null
        val last = retainedTimestampsMs.last()
        val hinted = durationHintMs?.takeIf { it > 0L }?.let { first + it }
        return maxOf(
            last + SizeBudget.MIN_FRAME_DURATION_MS,
            hinted ?: last + estimateIntervalMs(retainedTimestampsMs, fallbackMs),
        )
    }

    /** Which frames to keep so that timestamps strictly rise.
     *
     * libwebp turns the gap between consecutive timestamps into each frame's
     * on-screen duration, so two frames sharing a millisecond leave the first
     * lasting 0ms: encoded, paid for in bytes, and never displayed. WhatsApp
     * refuses any frame under [SizeBudget.MIN_FRAME_DURATION_MS], so a handful
     * of these makes a pack unaddable.
     *
     * They arise because timestamps are whole milliseconds and the sources are
     * not: a VP9 stream can re-show a frame, and any decoder whose frames fall
     * closer together than a millisecond collides once rounded. One real
     * Telegram clip produces nine of them out of 57.
     *
     * Where two frames share an instant the later one wins, since it is the
     * more current picture. A timestamp that goes backwards is treated the
     * same way rather than being reordered -- decoders do hand frames back out
     * of order, and inventing an order for them would be guessing.
     *
     * Collisions leave the maximum timestamp untouched, so an end timestamp
     * derived from the full timeline stays valid. A backwards timestamp can
     * lower it, which only lengthens the final frame against that fixed end
     * rather than changing the animation's span. */
    fun strictlyRisingIndices(timestampsMs: List<Long>): List<Int> {
        if (timestampsMs.size < 2) return List(timestampsMs.size) { it }
        val kept = mutableListOf(0)
        for (index in 1 until timestampsMs.size) {
            if (timestampsMs[index] > timestampsMs[kept.last()]) {
                kept += index
            } else {
                kept[kept.lastIndex] = index
            }
        }
        return kept
    }

    /** Roughly halves a frame list while retaining both visual endpoints.
     * Dropping the final frame on every even-sized retry changes the pose held
     * immediately before the loop returns to frame zero, which can introduce
     * a conversion-only loop jump. */
    fun halfFrameIndices(frameCount: Int): List<Int> {
        if (frameCount <= 0) return emptyList()
        if (frameCount <= 2) return List(frameCount) { it }
        val retainedCount = (frameCount + 1) / 2
        val lastIndex = frameCount - 1
        return List(retainedCount) { index ->
            (index.toLong() * lastIndex / (retainedCount - 1)).toInt()
        }
    }
}
