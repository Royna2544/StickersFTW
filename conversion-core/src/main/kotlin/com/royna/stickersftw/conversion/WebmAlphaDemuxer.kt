package com.royna.stickersftw.conversion

import java.io.File

/** One frame of a WebM video track: the primary bitstream, plus the separate
 * bitstream encoding its transparency when the file carries one. */
data class WebmFrame(
    val presentationTimeUs: Long,
    val colour: ByteArray,
    val alpha: ByteArray?,
    val isKeyframe: Boolean,
)

data class WebmAlphaTrack(
    val mime: String,
    val width: Int,
    val height: Int,
    val codecPrivate: ByteArray?,
    val frames: List<WebmFrame>,
)

/** A deliberately minimal Matroska reader for exactly one case: a Telegram
 * video sticker, whose transparency is a *second* VP9 bitstream stored once
 * per frame in the BlockGroup's BlockAdditional, with `Video > AlphaMode = 1`
 * announcing it.
 *
 * This exists because Android's `MediaExtractor` has no API for reading
 * block additions at all. It hands MediaCodec the primary block and the alpha
 * bitstream is simply unreachable through it, which is why every converted
 * video sticker came out opaque -- the transparent regions decode to whatever
 * the colour plane holds there, which for these files is black.
 *
 * Narrow on purpose. Anything MediaExtractor can already express stays its
 * job: this returns null for a file it doesn't fully understand -- no alpha
 * track, laced blocks, an unexpected codec -- so the caller falls back rather
 * than acting on a half-parsed file. */
object WebmAlphaDemuxer {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_ALPHA_MODE = 0x53C0L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_REFERENCE_BLOCK = 0xFBL
    private const val ID_BLOCK_ADDITIONS = 0x75A1L
    private const val ID_BLOCK_MORE = 0xA6L
    private const val ID_BLOCK_ADDITIONAL = 0xA5L

    private const val TRACK_TYPE_VIDEO = 1L
    private const val DEFAULT_TIMECODE_SCALE = 1_000_000L

    /** Files above this are refused outright: the parser reads the whole
     * thing into memory, which is fine for a sticker (a few hundred KB) and
     * not fine as a general-purpose video path. */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** Returns the track only when it genuinely carries per-frame alpha, so a
     * caller can treat a non-null result as "this needs the two-decoder
     * path". */
    fun readAlphaTrack(file: File): WebmAlphaTrack? = try {
        if (!isMatroska(file) || file.length() > MAX_FILE_BYTES) null else parse(file.readBytes())
    } catch (_: Exception) {
        null
    }

    /** Checked before reading the file in, so a locally picked mp4 costs four
     * bytes to reject rather than a full load. */
    private fun isMatroska(file: File): Boolean = file.inputStream().use { stream ->
        val header = ByteArray(4)
        stream.read(header) == 4 &&
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
    }

    private fun parse(data: ByteArray): WebmAlphaTrack? {
        val segment = findSegment(data) ?: return null
        var timecodeScale = DEFAULT_TIMECODE_SCALE
        var track: TrackInfo? = null
        val frames = mutableListOf<WebmFrame>()

        val reader = Reader(data, segment.first, segment.second)
        while (reader.hasMore()) {
            val element = reader.readElement() ?: return null
            when (element.id) {
                ID_INFO -> timecodeScale = readTimecodeScale(data, element) ?: timecodeScale
                ID_TRACKS -> track = readVideoTrack(data, element) ?: return null
                ID_CLUSTER -> {
                    val known = track ?: return null
                    if (!readCluster(data, element, known, timecodeScale, frames)) return null
                }
            }
        }

        val known = track ?: return null
        if (!known.hasAlpha) return null
        if (frames.isEmpty() || frames.none { it.alpha != null }) return null
        return WebmAlphaTrack(known.mime, known.width, known.height, known.codecPrivate, frames)
    }

    private fun findSegment(data: ByteArray): Pair<Int, Int>? {
        val reader = Reader(data, 0, data.size)
        while (reader.hasMore()) {
            val element = reader.readElement() ?: return null
            if (element.id == ID_SEGMENT) return element.start to element.end
        }
        return null
    }

    private fun readTimecodeScale(data: ByteArray, info: Element): Long? {
        val reader = Reader(data, info.start, info.end)
        while (reader.hasMore()) {
            val element = reader.readElement() ?: return null
            if (element.id == ID_TIMECODE_SCALE) return uint(data, element)
        }
        return null
    }

    private class TrackInfo(
        val number: Long,
        val mime: String,
        val width: Int,
        val height: Int,
        val codecPrivate: ByteArray?,
        val hasAlpha: Boolean,
    )

    private fun readVideoTrack(data: ByteArray, tracks: Element): TrackInfo? {
        val reader = Reader(data, tracks.start, tracks.end)
        while (reader.hasMore()) {
            val entry = reader.readElement() ?: return null
            if (entry.id != ID_TRACK_ENTRY) continue

            var number = -1L
            var type = -1L
            var codecId: String? = null
            var codecPrivate: ByteArray? = null
            var width = 0
            var height = 0
            var alphaMode = 0L

            val inner = Reader(data, entry.start, entry.end)
            while (inner.hasMore()) {
                val field = inner.readElement() ?: return null
                when (field.id) {
                    ID_TRACK_NUMBER -> number = uint(data, field)
                    ID_TRACK_TYPE -> type = uint(data, field)
                    ID_CODEC_ID -> codecId = string(data, field)
                    ID_CODEC_PRIVATE -> codecPrivate = data.copyOfRange(field.start, field.end)
                    ID_VIDEO -> {
                        val video = Reader(data, field.start, field.end)
                        while (video.hasMore()) {
                            val v = video.readElement() ?: return null
                            when (v.id) {
                                ID_PIXEL_WIDTH -> width = uint(data, v).toInt()
                                ID_PIXEL_HEIGHT -> height = uint(data, v).toInt()
                                ID_ALPHA_MODE -> alphaMode = uint(data, v)
                            }
                        }
                    }
                }
            }

            if (type != TRACK_TYPE_VIDEO) continue
            val mime = when (codecId) {
                "V_VP9" -> "video/x-vnd.on2.vp9"
                "V_VP8" -> "video/x-vnd.on2.vp8"
                else -> return null
            }
            if (width <= 0 || height <= 0) return null
            return TrackInfo(number, mime, width, height, codecPrivate, alphaMode == 1L)
        }
        return null
    }

    private fun readCluster(
        data: ByteArray,
        cluster: Element,
        track: TrackInfo,
        timecodeScale: Long,
        out: MutableList<WebmFrame>,
    ): Boolean {
        var clusterTimecode = 0L
        val reader = Reader(data, cluster.start, cluster.end)
        while (reader.hasMore()) {
            val element = reader.readElement() ?: return false
            when (element.id) {
                ID_TIMECODE -> clusterTimecode = uint(data, element)
                ID_SIMPLE_BLOCK -> {
                    val block = readBlock(data, element, track.number) ?: return false
                    if (block !== SKIPPED) {
                        out += block.toFrame(clusterTimecode, timecodeScale, null, block.keyframeFlag)
                    }
                }
                ID_BLOCK_GROUP -> {
                    var block: Block? = null
                    var alpha: ByteArray? = null
                    var referenced = false
                    val group = Reader(data, element.start, element.end)
                    while (group.hasMore()) {
                        val field = group.readElement() ?: return false
                        when (field.id) {
                            ID_BLOCK -> {
                                val parsed = readBlock(data, field, track.number) ?: return false
                                if (parsed !== SKIPPED) block = parsed
                            }
                            ID_REFERENCE_BLOCK -> referenced = true
                            ID_BLOCK_ADDITIONS -> alpha = readBlockAdditional(data, field) ?: return false
                        }
                    }
                    block?.let { out += it.toFrame(clusterTimecode, timecodeScale, alpha, !referenced) }
                }
            }
        }
        return true
    }

    /** The first BlockAdditional in the group. Matroska allows several
     * distinguished by BlockAddID, but WebM's alpha extension defines exactly
     * one (ID 1, the default when omitted), so taking the first is the whole
     * of the spec's surface here. */
    private fun readBlockAdditional(data: ByteArray, additions: Element): ByteArray? {
        val reader = Reader(data, additions.start, additions.end)
        while (reader.hasMore()) {
            val more = reader.readElement() ?: return null
            if (more.id != ID_BLOCK_MORE) continue
            val inner = Reader(data, more.start, more.end)
            while (inner.hasMore()) {
                val field = inner.readElement() ?: return null
                if (field.id == ID_BLOCK_ADDITIONAL) {
                    return data.copyOfRange(field.start, field.end)
                }
            }
        }
        return null
    }

    private class Block(
        val payload: ByteArray,
        val relativeTimecode: Int,
        val keyframeFlag: Boolean,
    ) {
        fun toFrame(
            clusterTimecode: Long,
            timecodeScale: Long,
            alpha: ByteArray?,
            keyframe: Boolean,
        ): WebmFrame {
            val ticks = clusterTimecode + relativeTimecode
            return WebmFrame(ticks * timecodeScale / 1000L, payload, alpha, keyframe)
        }
    }

    /** Sentinel for a block belonging to some other track, which is dropped
     * rather than treated as a parse failure. */
    private val SKIPPED = Block(ByteArray(0), 0, false)

    private fun readBlock(data: ByteArray, element: Element, trackNumber: Long): Block? {
        val reader = Reader(data, element.start, element.end)
        val number = reader.readVarInt(strip = true) ?: return null
        if (number != trackNumber) return SKIPPED
        if (reader.pos + 3 > element.end) return null
        val relative = ((data[reader.pos].toInt() and 0xFF) shl 8 or
            (data[reader.pos + 1].toInt() and 0xFF)).toShort().toInt()
        val flags = data[reader.pos + 2].toInt() and 0xFF
        // Lacing packs several frames into one block. Telegram's encoder does
        // not use it and supporting it properly means three more layouts, so
        // an unlaced-only parser that refuses the rest is the honest bound.
        if ((flags shr 1) and 0x03 != 0) return null
        val payloadStart = reader.pos + 3
        if (payloadStart > element.end) return null
        return Block(
            data.copyOfRange(payloadStart, element.end),
            relative,
            flags and 0x80 != 0,
        )
    }

    private class Element(val id: Long, val start: Int, val end: Int)

    private class Reader(val data: ByteArray, var pos: Int, val end: Int) {
        fun hasMore(): Boolean = pos < end

        fun readElement(): Element? {
            val id = readVarInt(strip = false) ?: return null
            val size = readVarInt(strip = true) ?: return null
            // An "unknown size" element runs to the end of its parent; only
            // Segment and Cluster are allowed to use it, and both are handled
            // by clamping to the parent we were given.
            val stop = if (size < 0) end else minOf(pos + size, end.toLong()).toInt()
            if (stop < pos) return null
            val element = Element(id, pos, stop)
            pos = stop
            return element
        }

        /** Reads an EBML variable-length integer. [strip] clears the marker
         * bit, which sizes need and element IDs must keep. Returns -1 for the
         * all-ones "unknown size" encoding. */
        fun readVarInt(strip: Boolean): Long? {
            if (pos >= end) return null
            val first = data[pos].toInt() and 0xFF
            if (first == 0) return null
            val length = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (pos + length > end) return null
            var value = if (strip) (first and ((1 shl (8 - length)) - 1)).toLong() else first.toLong()
            for (i in 1 until length) {
                value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
            }
            pos += length
            if (strip && value == unknownSizeFor(length)) return -1
            return value
        }

        private fun unknownSizeFor(length: Int): Long = (1L shl (7 * length)) - 1
    }

    private fun uint(data: ByteArray, element: Element): Long {
        var value = 0L
        for (i in element.start until element.end) {
            value = (value shl 8) or (data[i].toLong() and 0xFF)
        }
        return value
    }

    private fun string(data: ByteArray, element: Element): String =
        String(data, element.start, element.end - element.start, Charsets.US_ASCII).trimEnd('\u0000')
}
