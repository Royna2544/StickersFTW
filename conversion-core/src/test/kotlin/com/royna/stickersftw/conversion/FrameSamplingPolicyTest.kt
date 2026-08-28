package com.royna.stickersftw.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplingPolicyTest {
    @Test
    fun `a frame cap preserves the full animation duration`() {
        val timestamps = FrameSamplingPolicy.sampleTimestampsMs(
            durationMs = 3_000L,
            sourceFps = 60.0,
            maxFrames = 120,
            minFrameDurationMs = 8L,
        )

        assertEquals(120, timestamps.size)
        assertEquals(0L, timestamps.first())
        assertEquals(2_975L, timestamps.last())
        assertTrue(timestamps.zipWithNext().all { (first, second) -> second - first == 25L })
        assertEquals(3_000L, timestamps.last() + 25L)
    }

    @Test
    fun `an uncapped source keeps its natural cadence`() {
        val timestamps = FrameSamplingPolicy.sampleTimestampsMs(
            durationMs = 1_000L,
            sourceFps = 10.0,
            maxFrames = 120,
            minFrameDurationMs = 8L,
        )

        assertEquals((0L..900L step 100L).toList(), timestamps)
    }

    @Test
    fun `minimum frame duration also limits the output count`() {
        val timestamps = FrameSamplingPolicy.sampleTimestampsMs(
            durationMs = 100L,
            sourceFps = 1_000.0,
            maxFrames = 1_000,
            minFrameDurationMs = 20L,
        )

        assertEquals(listOf(0L, 20L, 40L, 60L, 80L), timestamps)
    }

    @Test
    fun `non-divisible duration distributes rounding across the full timeline`() {
        val timestamps = FrameSamplingPolicy.sampleTimestampsMs(
            durationMs = 1_001L,
            sourceFps = 60.0,
            maxFrames = 30,
            minFrameDurationMs = 8L,
        )

        assertEquals(30, timestamps.size)
        assertEquals(0L, timestamps.first())
        assertEquals(967L, timestamps.last())
        assertTrue(timestamps.zipWithNext().all { (first, second) -> second - first in 33L..34L })
    }

    @Test
    fun `invalid inputs return no timestamps`() {
        assertTrue(FrameSamplingPolicy.sampleTimestampsMs(0L, 30.0, 120, 8L).isEmpty())
        assertTrue(FrameSamplingPolicy.sampleTimestampsMs(1_000L, Double.NaN, 120, 8L).isEmpty())
        assertTrue(FrameSamplingPolicy.sampleTimestampsMs(1_000L, 30.0, 0, 8L).isEmpty())
        assertTrue(FrameSamplingPolicy.sampleTimestampsMs(1_000L, 30.0, 120, 0L).isEmpty())
    }

    @Test
    fun `interval estimate ignores one dropped frame gap`() {
        assertEquals(
            33L,
            FrameSamplingPolicy.estimateIntervalMs(listOf(0L, 33L, 99L, 132L, 165L)),
        )
    }

    @Test
    fun `interval estimate chooses normal cadence from two unequal gaps`() {
        assertEquals(33L, FrameSamplingPolicy.estimateIntervalMs(listOf(0L, 66L, 99L)))
    }

    @Test
    fun `interval estimate uses a positive fallback without usable gaps`() {
        assertEquals(17L, FrameSamplingPolicy.estimateIntervalMs(listOf(8L), fallbackMs = 17L))
        assertEquals(1L, FrameSamplingPolicy.estimateIntervalMs(listOf(8L, 8L), fallbackMs = 0L))
    }

    @Test
    fun `interior decoder gap needs no explicit trailing hold`() {
        assertNull(
            FrameSamplingPolicy.trailingHoldTimestampMs(
                wantedTimestampsMs = listOf(0L, 33L, 66L, 99L),
                decodedTimestampsMs = listOf(0L, 66L, 99L),
            ),
        )
    }

    @Test
    fun `missing final decoder output holds the previous frame to the intended tail`() {
        assertEquals(
            99L,
            FrameSamplingPolicy.trailingHoldTimestampMs(
                wantedTimestampsMs = listOf(0L, 33L, 66L, 99L),
                decodedTimestampsMs = listOf(0L, 33L, 66L),
            ),
        )
    }

    @Test
    fun `missing first decoder output restores duration after rebasing`() {
        assertEquals(
            132L,
            FrameSamplingPolicy.trailingHoldTimestampMs(
                wantedTimestampsMs = listOf(0L, 33L, 66L, 99L),
                decodedTimestampsMs = listOf(33L, 66L, 99L),
            ),
        )
    }

    @Test
    fun `one decoded frame remains a static result`() {
        assertNull(
            FrameSamplingPolicy.trailingHoldTimestampMs(
                wantedTimestampsMs = listOf(0L, 33L, 66L),
                decodedTimestampsMs = listOf(0L),
            ),
        )
    }
}
