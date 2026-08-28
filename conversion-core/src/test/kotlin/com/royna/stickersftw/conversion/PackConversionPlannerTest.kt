package com.royna.stickersftw.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackConversionPlannerTest {

    @Test
    fun `classifyPackIsAnimated is true when animated or video stickers are the majority`() {
        val types = listOf(
            StickerMediaType.AnimatedLottie,
            StickerMediaType.Video,
            StickerMediaType.Static,
        )
        assertTrue(PackConversionPlanner.classifyPackIsAnimated(types))
    }

    @Test
    fun `classifyPackIsAnimated is false when static stickers are the majority`() {
        val types = listOf(
            StickerMediaType.Static,
            StickerMediaType.Static,
            StickerMediaType.AnimatedLottie,
        )
        assertTrue(!PackConversionPlanner.classifyPackIsAnimated(types))
    }

    @Test
    fun `classifyPackIsAnimated breaks ties toward animated`() {
        val types = listOf(StickerMediaType.Static, StickerMediaType.Video)
        assertTrue(PackConversionPlanner.classifyPackIsAnimated(types))
    }

    @Test
    fun `applyCountRules rejects packs below the minimum`() {
        val result = PackConversionPlanner.applyCountRules(listOf(1, 2))
        assertTrue(result is PlannerResult.Rejected)
    }

    @Test
    fun `applyCountRules accepts a pack at the minimum unmodified`() {
        val items = listOf(1, 2, 3)
        val result = PackConversionPlanner.applyCountRules(items)
        assertTrue(result is PlannerResult.Ok)
        val ok = result as PlannerResult.Ok
        assertEquals(items, ok.items)
        assertEquals(null, ok.warning)
    }

    @Test
    fun `applyCountRules accepts a pack above the maximum unmodified`() {
        val items = (1..40).toList()
        val result = PackConversionPlanner.applyCountRules(items)
        assertTrue(result is PlannerResult.Ok)
        val ok = result as PlannerResult.Ok
        assertEquals(40, ok.items.size)
        assertEquals(null, ok.warning)
    }

    @Test
    fun `computePartRanges returns a single full range under the cap`() {
        val ranges = PackConversionPlanner.computePartRanges(20)
        assertEquals(listOf(0 until 20), ranges)
    }

    @Test
    fun `computePartRanges splits evenly instead of truncating`() {
        val ranges = PackConversionPlanner.computePartRanges(91)
        assertEquals(4, ranges.size)
        assertEquals(91, ranges.sumOf { it.last - it.first + 1 })
        for (range in ranges) {
            val size = range.last - range.first + 1
            assertTrue(size <= 30)
            assertTrue(size >= 3)
        }
    }

    @Test
    fun `computePartRanges covers the exact boundary without an empty trailing part`() {
        val ranges = PackConversionPlanner.computePartRanges(60)
        assertEquals(2, ranges.size)
        assertEquals(listOf(0 until 30, 30 until 60), ranges)
    }

    @Test
    fun `computePartRanges of zero or negative is empty`() {
        assertEquals(emptyList<IntRange>(), PackConversionPlanner.computePartRanges(0))
        assertEquals(emptyList<IntRange>(), PackConversionPlanner.computePartRanges(-5))
    }

    @Test
    fun `normalizeEmojis falls back to a default when none are given`() {
        assertEquals(listOf(SizeBudget.FALLBACK_EMOJI), PackConversionPlanner.normalizeEmojis(null))
        assertEquals(listOf(SizeBudget.FALLBACK_EMOJI), PackConversionPlanner.normalizeEmojis("   "))
    }

    @Test
    fun `normalizeEmojis splits on commas and whitespace and caps at three`() {
        val result = PackConversionPlanner.normalizeEmojis("😀, 😺 🚀,🌙,⭐")
        assertEquals(listOf("😀", "😺", "🚀"), result)
    }
}
