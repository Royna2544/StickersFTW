package com.royna.stickersftw.conversion

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds Matroska directly rather than shipping a real sticker as a fixture:
 * the parser's job is entirely structural, and a synthetic file can exercise
 * the shapes that matter -- including the ones a real file never would, like
 * lacing -- without committing someone else's artwork to the repo. */
class WebmAlphaDemuxerTest {
    private fun idBytes(id: Long): ByteArray {
        val bytes = ArrayDeque<Byte>()
        var value = id
        while (value > 0) {
            bytes.addFirst((value and 0xFF).toByte())
            value = value ushr 8
        }
        return bytes.toByteArray()
    }

    /** Always the 4-byte form, which is valid for any size used here and
     * keeps the builder free of length bookkeeping. */
    private fun sizeBytes(size: Int) = byteArrayOf(
        (0x10 or ((size ushr 24) and 0x0F)).toByte(),
        ((size ushr 16) and 0xFF).toByte(),
        ((size ushr 8) and 0xFF).toByte(),
        (size and 0xFF).toByte(),
    )

    private fun element(id: Long, payload: ByteArray) = idBytes(id) + sizeBytes(payload.size) + payload

    private fun uint(value: Long) = byteArrayOf(value.toByte())

    private fun block(trackNumber: Int, relative: Int, flags: Int, payload: ByteArray) = byteArrayOf(
        (0x80 or trackNumber).toByte(),
        ((relative shr 8) and 0xFF).toByte(),
        (relative and 0xFF).toByte(),
        flags.toByte(),
    ) + payload

    private fun trackEntry(alphaMode: Long) = element(
        0xAE,
        element(0xD7, uint(1)) +
            element(0x83, uint(1)) +
            element(0x86, "V_VP9".toByteArray()) +
            element(
                0xE0,
                element(0xB0, uint(64)) +
                    element(0xBA, uint(64)) +
                    element(0x53C0, uint(alphaMode)),
            ),
    )

    private fun writeWebm(
        alphaMode: Long,
        blocks: ByteArray,
        name: String,
        durationTicks: Double? = null,
    ): File {
        val info = element(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40)) +
            (durationTicks?.let { element(0x4489, ByteBuffer.allocate(8).putDouble(it).array()) }
                ?: ByteArray(0))
        val segment = element(0x1549A966, info) +
            element(0x1654AE6B, trackEntry(alphaMode)) +
            element(0x1F43B675, element(0xE7, uint(0)) + blocks)
        val file = File.createTempFile(name, ".webm")
        file.deleteOnExit()
        file.writeBytes(
            element(0x1A45DFA3, ByteArray(0)) + element(0x18538067, segment),
        )
        return file
    }

    private fun blockGroup(relative: Int, colour: ByteArray, alpha: ByteArray?) = element(
        0xA0,
        element(0xA1, block(1, relative, 0x00, colour)) +
            (
                alpha?.let {
                    element(0x75A1, element(0xA6, element(0xA5, it)))
                } ?: ByteArray(0)
                ),
    )

    @Test
    fun `pairs each frame with the alpha bitstream from its block addition`() {
        val file = writeWebm(
            alphaMode = 1,
            blocks = blockGroup(0, byteArrayOf(1, 1, 1), byteArrayOf(9, 9)) +
                blockGroup(40, byteArrayOf(2, 2, 2), byteArrayOf(8, 8)),
            name = "alpha",
        )

        val track = WebmAlphaDemuxer.readAlphaTrack(file)

        assertNotNull(track)
        requireNotNull(track)
        assertEquals("video/x-vnd.on2.vp9", track.mime)
        assertEquals(64, track.width)
        assertEquals(64, track.height)
        assertEquals(2, track.frames.size)
        assertTrue(track.frames[0].colour.contentEquals(byteArrayOf(1, 1, 1)))
        assertTrue(track.frames[0].alpha!!.contentEquals(byteArrayOf(9, 9)))
        assertTrue(track.frames[1].alpha!!.contentEquals(byteArrayOf(8, 8)))
    }

    /** TimecodeScale is 1_000_000ns per tick, so a relative timecode of 40
     * is 40ms, and the demuxer reports microseconds. */
    @Test
    fun `scales block timecodes into microseconds`() {
        val file = writeWebm(
            alphaMode = 1,
            blocks = blockGroup(0, byteArrayOf(1), byteArrayOf(9)) +
                blockGroup(40, byteArrayOf(2), byteArrayOf(8)),
            name = "timecodes",
        )

        val track = requireNotNull(WebmAlphaDemuxer.readAlphaTrack(file))

        assertEquals(0L, track.frames[0].presentationTimeUs)
        assertEquals(40_000L, track.frames[1].presentationTimeUs)
    }

    @Test
    fun `reads exact declared duration using the timecode scale`() {
        val file = writeWebm(
            alphaMode = 1,
            blocks = blockGroup(0, byteArrayOf(1), byteArrayOf(9)) +
                blockGroup(2_800, byteArrayOf(2), byteArrayOf(8)),
            name = "duration",
            durationTicks = 2_833.0,
        )

        val track = requireNotNull(WebmAlphaDemuxer.readAlphaTrack(file))

        assertEquals(2_833_000L, track.durationUs)
        assertEquals(2_800_000L, track.frames.last().presentationTimeUs)
    }

    @Test
    fun `returns null when the track declares no alpha`() {
        val file = writeWebm(
            alphaMode = 0,
            blocks = blockGroup(0, byteArrayOf(1), byteArrayOf(9)),
            name = "noalpha",
        )

        assertNull(WebmAlphaDemuxer.readAlphaTrack(file))
    }

    /** AlphaMode alone isn't enough: without block additions there is nothing
     * to decode, and claiming the alpha path would strand the caller. */
    @Test
    fun `returns null when alpha is declared but no block carries it`() {
        val file = writeWebm(
            alphaMode = 1,
            blocks = blockGroup(0, byteArrayOf(1), null),
            name = "declared-only",
        )

        assertNull(WebmAlphaDemuxer.readAlphaTrack(file))
    }

    @Test
    fun `returns null for laced blocks rather than misreading them`() {
        val laced = element(
            0xA0,
            element(0xA1, block(1, 0, 0x02, byteArrayOf(1, 1))) +
                element(0x75A1, element(0xA6, element(0xA5, byteArrayOf(9)))),
        )
        val file = writeWebm(alphaMode = 1, blocks = laced, name = "laced")

        assertNull(WebmAlphaDemuxer.readAlphaTrack(file))
    }

    @Test
    fun `returns null for a file that is not matroska`() {
        val file = File.createTempFile("notwebm", ".mp4")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte()))

        assertNull(WebmAlphaDemuxer.readAlphaTrack(file))
    }
}
