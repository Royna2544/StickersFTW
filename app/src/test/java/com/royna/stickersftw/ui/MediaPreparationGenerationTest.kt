package com.royna.stickersftw.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreparationGenerationTest {
    @Test
    fun `new preparation supersedes older suspended preparation`() {
        val generation = MediaPreparationGeneration()
        val first = generation.next()
        val second = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun `cancellation invalidates current preparation`() {
        val generation = MediaPreparationGeneration()
        val current = generation.next()

        generation.invalidate()

        assertFalse(generation.isCurrent(current))
    }
}
