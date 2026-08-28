package com.royna.stickersftw.conversion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerTypeClassifierTest {
    @Test
    fun `classifies supported content types and ignores parameters`() {
        assertEquals(StickerMediaType.Static, StickerTypeClassifier.classify("image/webp"))
        assertEquals(StickerMediaType.Static, StickerTypeClassifier.classify("image/jpeg; charset=binary"))
        assertEquals(StickerMediaType.Video, StickerTypeClassifier.classify("video/webm"))
        assertEquals(
            StickerMediaType.AnimatedLottie,
            StickerTypeClassifier.classify("application/x-tgsticker"),
        )
    }

    @Test
    fun `returns unknown for absent or unsupported content types`() {
        assertEquals(StickerMediaType.Unknown, StickerTypeClassifier.classify(null))
        assertEquals(StickerMediaType.Unknown, StickerTypeClassifier.classify("application/octet-stream"))
    }

    @Test
    fun `gzip magic reclassifies an unknown file as a lottie sticker`() {
        val file = temporaryFile(byteArrayOf(0x1F, 0x8B.toByte(), 0x08))

        assertEquals(StickerMediaType.AnimatedLottie, StickerTypeClassifier.reclassifyUnknown(file))
    }

    @Test
    fun `a non-gzip unknown file falls back to static`() {
        val file = temporaryFile(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()))

        assertEquals(StickerMediaType.Static, StickerTypeClassifier.reclassifyUnknown(file))
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("sticker-classifier", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
}
