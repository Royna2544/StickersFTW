package com.royna.stickersftw.ui

import com.royna.stickersftw.conversion.SizeBudget
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRangeTest {
    @Test
    fun `range queue includes short and long videos with known durations`() {
        assertEquals(
            listOf(0, 2),
            knownVideoRangeIndices(listOf(2_000L, null, 21_000L, 0L)),
        )
    }

    @Test
    fun `new clips default to their first ten seconds`() {
        assertEquals(
            VideoRange(0L, SizeBudget.MAX_TOTAL_DURATION_MS),
            initialVideoRange(42_000L, 0L, 0L),
        )
    }

    @Test
    fun `short clips select their full duration`() {
        assertEquals(
            VideoRange(0L, 320L),
            initialVideoRange(320L, 100L, 0L),
        )
    }

    @Test
    fun `saved range is clamped inside changed source`() {
        assertEquals(
            VideoRange(5_000L, 3_000L),
            initialVideoRange(8_000L, 7_000L, 3_000L),
        )
    }

    @Test
    fun `moving end enforces minimum range`() {
        assertEquals(
            VideoRange(2_000L, MIN_VIDEO_RANGE_MS),
            adjustVideoRange(
                current = VideoRange(2_000L, 2_000L),
                requestedStartMs = 2_000L,
                requestedEndMs = 2_100L,
                sourceDurationMs = 20_000L,
            ),
        )
    }

    @Test
    fun `moving start enforces maximum range`() {
        assertEquals(
            VideoRange(5_000L, SizeBudget.MAX_TOTAL_DURATION_MS),
            adjustVideoRange(
                current = VideoRange(12_000L, 3_000L),
                requestedStartMs = 1_000L,
                requestedEndMs = 15_000L,
                sourceDurationMs = 20_000L,
            ),
        )
    }
}
