package com.royna.stickersftw.data

import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSignatureTest {

    @Test
    fun `stored identity matching survives locator rotation and backend namespace switches`() {
        val direct = identitySticker(id = "rotated-file-id", stableId = "stable-42")
        val server = identitySticker(id = "stable-42", stableId = null)

        assertTrue(SourceSignature.matchesAnyStoredIdentity(direct, setOf("stable-42")))
        assertTrue(SourceSignature.matchesAnyStoredIdentity(server, setOf("stable-42")))
        assertTrue(SourceSignature.matchesAnyStoredIdentity(direct, setOf("rotated-file-id")))
        assertFalse(SourceSignature.matchesAnyStoredIdentity(direct, setOf("different")))
    }

    private fun setOf3(title: String = "Ducks") = StickerSetDto(
        name = "ducks",
        title = title,
        stickers = listOf(
            sticker("a", "🦆"),
            sticker("b", "😂"),
            sticker("c", "❤️"),
        ),
    )

    private fun sticker(suffix: String, emoji: String) = StickerDto(
        id = "file-$suffix",
        stableId = "stable-$suffix",
        width = 512,
        height = 512,
        size = 100,
        emoji = emoji,
    )

    private fun identitySticker(id: String, stableId: String?) = StickerDto(
        id = id,
        stableId = stableId,
        width = 512,
        height = 512,
        size = 100,
    )

    @Test
    fun `identical sets produce identical signatures`() {
        assertEquals(SourceSignature.compute(setOf3()), SourceSignature.compute(setOf3()))
    }

    @Test
    fun `changing only downloadable file ids keeps the signature stable`() {
        val base = setOf3()
        val refreshedLocators = base.copy(
            stickers = base.stickers.map { it.copy(id = "refreshed-${it.id}") },
        )

        assertEquals(SourceSignature.compute(base), SourceSignature.compute(refreshedLocators))
    }

    @Test
    fun `legacy locator signature matches while locators remain unchanged`() {
        val current = setOf3()
        val legacy = current.copy(
            stickers = current.stickers.map { it.copy(stableId = null) },
        )

        assertTrue(SourceSignature.matches(current, SourceSignature.compute(legacy)))
    }

    @Test
    fun `changing a stable file identity flips the signature`() {
        val base = setOf3()
        val changed = base.copy(
            stickers = base.stickers.map {
                if (it.stableId == "stable-b") it.copy(stableId = "replacement-b") else it
            },
        )

        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(changed))
    }

    @Test
    fun `downloadable id remains the fallback when no stable identity is available`() {
        val stableSet = setOf3()
        val base = stableSet.copy(stickers = stableSet.stickers.map { it.copy(stableId = null) })
        val changed = base.copy(
            stickers = base.stickers.mapIndexed { index, sticker ->
                if (index == 1) sticker.copy(id = "replacement-file-b") else sticker
            },
        )

        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(changed))
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
        val added = base.copy(
            stickers = base.stickers + StickerDto(
                id = "file-d",
                stableId = "stable-d",
                width = 512,
                height = 512,
                size = 100,
                emoji = "🔥",
            ),
        )
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
            stickers = base.stickers.map { if (it.stableId == "stable-b") it.copy(emoji = "😢") else it },
        )
        assertNotEquals(SourceSignature.compute(base), SourceSignature.compute(changed))
    }
}
