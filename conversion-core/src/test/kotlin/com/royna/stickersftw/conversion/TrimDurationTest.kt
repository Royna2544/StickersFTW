package com.royna.stickersftw.conversion

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimDurationTest {
    @Test
    fun zeroUsesDestinationMaximum() {
        assertEquals(10_000L, effectiveTrimDurationMs(0L, 10_000L))
        assertEquals(3_000L, effectiveTrimDurationMs(0L, 3_000L))
    }

    @Test
    fun positiveSelectionIsKeptWithinDestinationMaximum() {
        assertEquals(2_250L, effectiveTrimDurationMs(2_250L, 10_000L))
        assertEquals(3_000L, effectiveTrimDurationMs(8_000L, 3_000L))
    }

    @Test
    fun invalidNegativeDurationFallsBackSafely() {
        assertEquals(10_000L, effectiveTrimDurationMs(-1L, 10_000L))
    }
}
