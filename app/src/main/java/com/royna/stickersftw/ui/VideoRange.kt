package com.royna.stickersftw.ui

import com.royna.stickersftw.conversion.SizeBudget
import kotlin.math.abs

internal const val MIN_VIDEO_RANGE_MS = 500L

/** A source-relative clip selection. [durationMs] is always positive. */
internal data class VideoRange(
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/** Keeps every video the duration probe could read, including short clips. */
internal fun knownVideoRangeIndices(durationsMs: List<Long?>): List<Int> =
    durationsMs.indices.filter { index -> (durationsMs[index] ?: 0L) > 0L }

/** Restores a saved range, or chooses the first target-sized section. */
internal fun initialVideoRange(
    sourceDurationMs: Long,
    savedStartMs: Long,
    savedDurationMs: Long,
): VideoRange {
    require(sourceDurationMs > 0L)
    val maximum = minOf(sourceDurationMs, SizeBudget.MAX_TOTAL_DURATION_MS)
    val minimum = minOf(sourceDurationMs, MIN_VIDEO_RANGE_MS)
    val duration = if (savedDurationMs > 0L) {
        savedDurationMs.coerceIn(minimum, maximum)
    } else {
        maximum
    }
    val start = savedStartMs.coerceIn(0L, sourceDurationMs - duration)
    return VideoRange(start, duration)
}

/**
 * Applies a two-thumb gesture while preserving the 0.5–10 second limits.
 * The thumb that moved furthest wins when the requested span crosses a limit.
 */
internal fun adjustVideoRange(
    current: VideoRange,
    requestedStartMs: Long,
    requestedEndMs: Long,
    sourceDurationMs: Long,
): VideoRange {
    require(sourceDurationMs > 0L)
    val minimum = minOf(sourceDurationMs, MIN_VIDEO_RANGE_MS)
    val maximum = minOf(sourceDurationMs, SizeBudget.MAX_TOTAL_DURATION_MS)
    var start = requestedStartMs.coerceIn(0L, sourceDurationMs)
    var end = requestedEndMs.coerceIn(start, sourceDurationMs)
    val movedStart = abs(start - current.startMs) >= abs(end - current.endMs)

    if (end - start < minimum) {
        if (movedStart) {
            start = (end - minimum).coerceAtLeast(0L)
            end = (start + minimum).coerceAtMost(sourceDurationMs)
        } else {
            end = (start + minimum).coerceAtMost(sourceDurationMs)
            start = (end - minimum).coerceAtLeast(0L)
        }
    }
    if (end - start > maximum) {
        if (movedStart) {
            start = end - maximum
        } else {
            end = start + maximum
        }
    }
    return VideoRange(start, end - start)
}
