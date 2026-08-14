package com.royna.stickersftw.data

import com.royna.stickersftw.conversion.SizeBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun eitherWhatsappClientCanReportPackInstalled() {
        assertEquals(true, combineWhatsappWhitelistStates(consumer = false, business = true))
        assertEquals(true, combineWhatsappWhitelistStates(consumer = true, business = false))
        assertEquals(false, combineWhatsappWhitelistStates(consumer = false, business = null))
        assertEquals(false, combineWhatsappWhitelistStates(consumer = null, business = false))
        assertNull(combineWhatsappWhitelistStates(consumer = null, business = null))
    }

    @Test
    fun publishFinalizationRejectsAnyNewerContentRevision() {
        assertTrue(canFinalizePublish(expectedRevision = null, currentRevision = 8))
        assertTrue(canFinalizePublish(expectedRevision = 8, currentRevision = 8))
        assertFalse(canFinalizePublish(expectedRevision = 8, currentRevision = 9))
    }
}
