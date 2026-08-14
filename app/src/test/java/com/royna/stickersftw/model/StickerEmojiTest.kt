package com.royna.stickersftw.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerEmojiTest {
    @Test
    fun acceptsSeparatedAndComposedEmoji() {
        assertEquals(listOf("😀", "🎬", "✨"), parseStickerEmojis("😀 🎬,✨"))
        assertEquals(listOf("👨‍👩‍👧‍👦", "👍🏽"), parseStickerEmojis("👨‍👩‍👧‍👦👍🏽"))
    }

    @Test
    fun rejectsPlainTextAndMoreThanThreeEmoji() {
        assertNull(parseStickerEmojis("hello"))
        assertNull(parseStickerEmojis("😀😁😂🤣"))
    }
}
