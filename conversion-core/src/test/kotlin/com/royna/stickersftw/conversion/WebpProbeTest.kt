package com.royna.stickersftw.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Container parsing, checked against bytes built here rather than against
 * fixture files, so every field under test is one this file chose.
 *
 * The offsets are the whole point. Reading a duration or a canvas height from
 * the wrong place does not fail loudly -- it produces a plausible number, and
 * a pack gets declared healthy on the strength of it.
 */
class WebpProbeTest {

    private fun le32(value: Int) = ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() }
    private fun le24(value: Int) = ByteArray(3) { ((value shr (8 * it)) and 0xFF).toByte() }

    private fun chunk(tag: String, payload: ByteArray): ByteArray {
        val padding = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return tag.toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload + padding
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val payload = "WEBP".toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { a, b -> a + b }
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload
    }

    private fun vp8x(width: Int, height: Int, animated: Boolean, alpha: Boolean): ByteArray {
        var flags = 0
        if (animated) flags = flags or 0x02
        if (alpha) flags = flags or 0x10
        return chunk(
            "VP8X",
            byteArrayOf(flags.toByte(), 0, 0, 0) + le24(width - 1) + le24(height - 1),
        )
    }

    /** x, y, width-1, height-1, duration, flags -- duration is the fifth
     * field, not the first. */
    private fun anmf(durationMs: Int, size: Int = 512): ByteArray = chunk(
        "ANMF",
        le24(0) + le24(0) + le24(size - 1) + le24(size - 1) + le24(durationMs) + byteArrayOf(0),
    )

    @Test
    fun readsAnAnimationsFramesAndTheirDurations() {
        val info = WebpProbe.read(
            riff(vp8x(512, 512, animated = true, alpha = true), anmf(40), anmf(40), anmf(70)),
        )!!

        assertTrue(info.isAnimated)
        assertTrue(info.hasAlpha)
        assertEquals(listOf(40, 40, 70), info.frameDurationsMs)
        assertEquals(3, info.frameCount)
        assertEquals(150, info.totalDurationMs)
        assertEquals(512, info.width)
        assertEquals(512, info.height)
    }

    /** A still is one frame, even though it has no ANMF chunk to count. */
    @Test
    fun aStillImageCountsAsOneFrame() {
        val info = WebpProbe.read(riff(vp8x(512, 512, animated = false, alpha = true)))!!

        assertFalse(info.isAnimated)
        assertEquals(1, info.frameCount)
        assertEquals(emptyList<Int>(), info.frameDurationsMs)
        assertEquals(0, info.totalDurationMs)
    }

    /** Width and height are separate 24-bit fields three bytes apart. Reading
     * the height from the wrong offset yields a huge number that still looks
     * like a number -- which is exactly how it went unnoticed by hand. */
    @Test
    fun widthAndHeightAreNotConfused() {
        val info = WebpProbe.read(riff(vp8x(512, 384, animated = false, alpha = false)))!!

        assertEquals(512, info.width)
        assertEquals(384, info.height)
    }

    @Test
    fun readsTheLargestCanvasTheFormatAllows() {
        val info = WebpProbe.read(riff(vp8x(16384, 16384, animated = false, alpha = false)))!!

        assertEquals(16384, info.width)
        assertEquals(16384, info.height)
    }

    /** Chunks are padded to an even length. A parser that ignores the pad
     * byte walks into the middle of the next chunk and reads nonsense from
     * then on. */
    @Test
    fun anOddLengthChunkDoesNotDerailTheOnesAfterIt() {
        val odd = chunk("ICCP", ByteArray(5) { 1 })
        val info = WebpProbe.read(
            riff(vp8x(512, 512, animated = true, alpha = false), odd, anmf(40), anmf(60)),
        )!!

        assertEquals(listOf(40, 60), info.frameDurationsMs)
    }

    @Test
    fun readsDimensionsFromASimpleLossyFile() {
        val payload = byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) +
            byteArrayOf(0, 2, 0, 2) // 512 x 512, little-endian 14-bit
        val info = WebpProbe.read(riff(chunk("VP8 ", payload)))!!

        assertEquals(512, info.width)
        assertEquals(512, info.height)
        assertFalse(info.isAnimated)
    }

    @Test
    fun readsDimensionsFromALosslessFile() {
        val packed = (512 - 1) or ((384 - 1) shl 14) or (1 shl 28)
        val info = WebpProbe.read(riff(chunk("VP8L", byteArrayOf(0x2F) + le32(packed))))!!

        assertEquals(512, info.width)
        assertEquals(384, info.height)
        assertTrue(info.hasAlpha)
    }

    /** A frame that never gets shown is the defect this whole check exists
     * for, so a zero has to survive parsing as a zero rather than being
     * skipped or defaulted. */
    @Test
    fun zeroDurationFramesAreReportedNotSwallowed() {
        val info = WebpProbe.read(
            riff(vp8x(512, 512, animated = true, alpha = false), anmf(0), anmf(40), anmf(0)),
        )!!

        assertEquals(listOf(0, 40, 0), info.frameDurationsMs)
    }

    @Test
    fun theByteCountIsTheWholeFile() {
        val bytes = riff(vp8x(512, 512, animated = false, alpha = false))

        assertEquals(bytes.size.toLong(), WebpProbe.read(bytes)!!.byteCount)
    }

    /** An interrupted download leaves a file whose header still claims the
     * full length. Reading it must stop, not throw. */
    @Test
    fun aTruncatedFileIsReadAsFarAsItGoes() {
        val whole = riff(vp8x(512, 512, animated = true, alpha = false), anmf(40), anmf(60))
        val truncated = whole.copyOf(whole.size - 12)

        val info = WebpProbe.read(truncated)

        assertEquals(listOf(40), info?.frameDurationsMs)
    }

    @Test
    fun nonWebpInputIsRejectedRatherThanGuessedAt() {
        assertNull(WebpProbe.read(ByteArray(0)))
        assertNull(WebpProbe.read(ByteArray(64)))
        assertNull(WebpProbe.read("not a webp file at all".toByteArray()))
        assertNull(
            "a RIFF container that is not WEBP",
            WebpProbe.read("RIFF".toByteArray() + le32(4) + "AVI ".toByteArray()),
        )
    }

    /** No chunk ever gives a size, so there is nothing to report. */
    @Test
    fun aWebpWithNoRecognisableChunkIsRejected() {
        assertNull(WebpProbe.read(riff(chunk("XYZW", ByteArray(8)))))
    }

    /** A corrupt size field must not send the walk past the end of the array. */
    @Test
    fun anImpossibleChunkSizeDoesNotThrow() {
        val header = "RIFF".toByteArray(Charsets.US_ASCII) + le32(0x7FFFFFF0) +
            "WEBP".toByteArray(Charsets.US_ASCII) +
            "VP8X".toByteArray(Charsets.US_ASCII) + le32(0x7FFFFF00)

        assertNull(WebpProbe.read(header))
    }
}
