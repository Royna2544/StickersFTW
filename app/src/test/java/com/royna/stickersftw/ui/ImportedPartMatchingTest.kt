package com.royna.stickersftw.ui

import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.StickerPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedPartMatchingTest {
    @Test
    fun `split subset retains duplicate ownership of its source part`() {
        val split = StickerPack(
            id = "split",
            title = "Split",
            author = "Tester",
            origin = PackOrigin.Imported,
            stickerCount = 3,
            isAnimated = false,
            telegramSetName = "set_by_bot",
            importPartIndex = -3,
            sourcePartIndex = 1,
        )

        assertTrue(split.matchesImportedPart("set_by_bot", 1))
        assertFalse(split.matchesImportedPart("set_by_bot", 0))
        assertFalse(split.matchesImportedPart("other_set", 1))
    }

    @Test
    fun `ordinary part still matches its direct index`() {
        val ordinary = StickerPack(
            id = "ordinary",
            title = "Ordinary",
            author = "Tester",
            origin = PackOrigin.Imported,
            stickerCount = 3,
            isAnimated = false,
            telegramSetName = "set_by_bot",
            importPartIndex = 2,
        )

        assertTrue(ordinary.matchesImportedPart("set_by_bot", 2))
    }
}
