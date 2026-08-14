package com.royna.stickersftw.data

import com.royna.stickersftw.conversion.SizeBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerEditorRulesTest {
    @Test
    fun emojisAreTrimmedDeduplicatedAndCapped() {
        assertEquals(
            "😀,🎬,✨",
            normalizeStickerEmojis(listOf(" 😀 🎬 ", "😀", "✨", "ignored")),
        )
    }

    @Test
    fun blankEmojiSelectionGetsRequiredFallback() {
        assertEquals(SizeBudget.FALLBACK_EMOJI, normalizeStickerEmojis(listOf("", "  ")))
    }

    @Test
    fun composedEmojiStayWholeWhileWhitespaceSeparatesTags() {
        assertEquals(
            "👨‍👩‍👧‍👦,👍🏽",
            normalizeStickerEmojis(listOf("👨‍👩‍👧‍👦 👍🏽")),
        )
    }

    @Test
    fun reorderRequiresEveryCurrentIdExactlyOnce() {
        assertEquals(listOf(3L, 1L, 2L), validatedStickerOrder(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L)))
        assertNull(validatedStickerOrder(listOf(1L, 2L, 3L), listOf(1L, 1L, 3L)))
        assertNull(validatedStickerOrder(listOf(1L, 2L, 3L), listOf(1L, 2L)))
        assertNull(validatedStickerOrder(listOf(1L, 2L, 3L), listOf(1L, 2L, 4L)))
    }
}
