package com.royna.stickersftw.data

import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSignatureTest {

    private fun setOf3(title: String = "Ducks") = StickerSetDto(
        name = "ducks",
        title = title,
        stickers = listOf(
            StickerDto(id = "a", width = 512, height = 512, size = 100, emoji = "🦆"),
            StickerDto(id = "b", width = 512, height = 512, size = 100, emoji = "😂"),
            StickerDto(id = "c", width = 512, height = 512, size = 100, emoji = "❤️"),
        ),
    )

    @Test
    fun `identical sets produce identical signatures`() {
        assertEquals(SourceSignature.compute(setOf3()), SourceSignature.compute(setOf3()))
    }

    @Test
    fun `title change flips the signature`() {
        assertNotEquals(
            SourceSignature.compute(setOf3(title = "Ducks")),
            SourceSignature.compute(setOf3(title = "Ducks 2")),
        )
    }

    @Test
    fun `added sticker flips the signature`() {
        val base = setOf3()
        val added = base.copy(stickers = base.stickers + StickerDto(id = "d", width = 512, height = 512, size = 100, emoji = "🔥"))
        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(added))
    }

    @Test
    fun `removed sticker flips the signature`() {
        val base = setOf3()
        val removed = base.copy(stickers = base.stickers.dropLast(1))
        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(removed))
    }

    @Test
    fun `reordered stickers flips the signature`() {
        val base = setOf3()
        val reordered = base.copy(stickers = base.stickers.reversed())
        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(reordered))
    }

    @Test
    fun `emoji change flips the signature`() {
        val base = setOf3()
        val changed = base.copy(
            stickers = base.stickers.map { if (it.id == "b") it.copy(emoji = "😢") else it },
        )
        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(changed))
    }
}
