package com.royna.stickersftw.conversion

import java.io.File

/** What a WebP file actually is, read from its own bytes.
 *
 * Every field here is something WhatsApp checks and rejects a whole pack
 * over, and none of it can be trusted from the caller's intent: the encoder
 * decides what it wrote, and it can silently collapse a multi-frame clip into
 * a still. Asking the file is the only answer that cannot be wrong. */
data class WebpInfo(
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val hasAlpha: Boolean,
    /** One entry per ANMF chunk, in file order. Empty for a still image. */
    val frameDurationsMs: List<Int>,
    val byteCount: Long,
) {
    /** A still image is one frame; an animation is however many it stored. */
    val frameCount: Int get() = if (frameDurationsMs.isEmpty()) 1 else frameDurationsMs.size

    val totalDurationMs: Int get() = frameDurationsMs.sum()
}

/** Reads the parts of the WebP container that decide whether WhatsApp will
 * accept a pack.
 *
 * Written out rather than delegating to an image library because this has to
 * run on a desktop JVM as well as on Android, and because the interesting
 * facts -- per-frame durations, the animation flag -- are container-level and
 * most decoders throw them away.
 *
 * The layout is RIFF: "RIFF", a 32-bit little-endian payload size, "WEBP",
 * then a sequence of chunks each with a 4-byte tag, a 32-bit little-endian
 * size, and that many payload bytes padded to an even length. */
object WebpProbe {
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8

    private const val ANIMATION_FLAG = 0x02
    private const val ALPHA_FLAG = 0x10

    fun read(file: File): WebpInfo? = try {
        read(file.readBytes())
    } catch (_: Exception) {
        null
    }

    fun read(bytes: ByteArray): WebpInfo? {
        if (bytes.size < RIFF_HEADER_BYTES + CHUNK_HEADER_BYTES) return null
        if (tagAt(bytes, 0) != "RIFF" || tagAt(bytes, 8) != "WEBP") return null

        // The RIFF size counts everything after itself, so the payload ends
        // 8 bytes past it. Clamped to the real length: a truncated file
        // still reports a full-length header, and walking past the end of
        // the array on the strength of that would throw rather than report.
        val declaredEnd = CHUNK_HEADER_BYTES + uInt32(bytes, 4)
        val end = minOf(declaredEnd, bytes.size.toLong()).toInt()

        var animated = false
        var alpha = false
        var width = 0
        var height = 0
        val durations = mutableListOf<Int>()

        var position = RIFF_HEADER_BYTES
        while (position + CHUNK_HEADER_BYTES <= end) {
            val tag = tagAt(bytes, position)
            val declaredSize = uInt32(bytes, position + 4)
            val payload = position + CHUNK_HEADER_BYTES
            // A chunk claiming to run past the end of the file is where a
            // truncated download stops being readable; take what was parsed
            // so far rather than throwing.
            if (payload + declaredSize > end) break
            val size = declaredSize.toInt()

            when (tag) {
                // Extended format: the only one that carries the animation and
                // alpha flags, and the canvas size for an animation.
                "VP8X" -> if (payload + 10 <= end) {
                    val flags = bytes[payload].toInt()
                    animated = flags and ANIMATION_FLAG != 0
                    alpha = flags and ALPHA_FLAG != 0
                    width = uInt24(bytes, payload + 4) + 1
                    height = uInt24(bytes, payload + 7) + 1
                }
                // Animation frame. Its duration sits at a fixed offset, after
                // the frame's own x/y/width/height.
                "ANMF" -> if (payload + 15 <= end) {
                    durations += uInt24(bytes, payload + 12)
                }
                // Simple lossy. Only trusted for dimensions when VP8X did not
                // already give the canvas size, since inside an animation this
                // describes one frame rather than the canvas.
                "VP8 " -> if (width == 0) readLossyDimensions(bytes, payload, end)?.let {
                    width = it.first
                    height = it.second
                }
                // Simple lossless.
                "VP8L" -> if (width == 0) readLosslessDimensions(bytes, payload, end)?.let {
                    width = it.first
                    height = it.second
                    alpha = it.third
                }
                "ALPH" -> alpha = true
            }

            // Chunks are padded to an even length.
            position = payload + size + (size and 1)
        }

        if (width <= 0 || height <= 0) return null
        return WebpInfo(
            width = width,
            height = height,
            isAnimated = animated,
            hasAlpha = alpha,
            frameDurationsMs = durations,
            byteCount = bytes.size.toLong(),
        )
    }

    /** VP8 keyframe header: a 3-byte frame tag, the 3-byte start code
     * 9d 01 2a, then 14-bit width and height each with 2 scaling bits above
     * them. */
    private fun readLossyDimensions(bytes: ByteArray, payload: Int, end: Int): Pair<Int, Int>? {
        if (payload + 10 > end) return null
        if (bytes[payload + 3] != 0x9D.toByte() ||
            bytes[payload + 4] != 0x01.toByte() ||
            bytes[payload + 5] != 0x2A.toByte()
        ) {
            return null
        }
        val width = uInt16(bytes, payload + 6) and 0x3FFF
        val height = uInt16(bytes, payload + 8) and 0x3FFF
        return width to height
    }

    /** VP8L header: the signature byte 0x2f, then 14 bits of width-1, 14 bits
     * of height-1 and an alpha bit, packed little-endian. */
    private fun readLosslessDimensions(bytes: ByteArray, payload: Int, end: Int): Triple<Int, Int, Boolean>? {
        if (payload + 5 > end) return null
        if (bytes[payload] != 0x2F.toByte()) return null
        val packed = uInt32(bytes, payload + 1)
        val width = (packed and 0x3FFF).toInt() + 1
        val height = ((packed shr 14) and 0x3FFF).toInt() + 1
        val hasAlpha = (packed shr 28) and 0x1L == 1L
        return Triple(width, height, hasAlpha)
    }

    private fun tagAt(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun uInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun uInt24(bytes: ByteArray, offset: Int): Int =
        (0..2).sumOf { (bytes[offset + it].toInt() and 0xFF) shl (8 * it) }

    /** Returned as Long because a RIFF size is unsigned 32-bit and would go
     * negative in an Int for anything over 2GB -- which only a corrupt file
     * would claim, and which should read as "too big", not as "negative". */
    private fun uInt32(bytes: ByteArray, offset: Int): Long =
        (0..3).sumOf { (bytes[offset + it].toLong() and 0xFF) shl (8 * it) }
}
