package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Decodes frames out of a video clip via a manual MediaCodec pipeline --
 * used both to reduce a fetched .webm Telegram sticker to WhatsApp-ready
 * frames, and to source frames for a Telegram video-sticker push from a
 * locally picked clip.
 *
 * This deliberately does NOT use [android.media.MediaMetadataRetriever]'s
 * convenience frame-sampling APIs ([android.media.MediaMetadataRetriever.getFrameAtTime]
 * or [android.media.MediaMetadataRetriever.getFrameAtIndex]). Both were tried
 * and both have the same real-hardware failure mode for Telegram's
 * sparsely-keyframed VP9 clips: on-device testing showed getFrameAtIndex
 * returning a distinct, non-null Bitmap for every sequential index -- with
 * pixel-identical content every time, silently stuck on one internal
 * (keyframe) frame despite reporting success. Frame *count* alone can't
 * detect this; only decoding the real bitstream can. Decoding the track from
 * the start with a real MediaCodec instance -- exactly what a video player
 * does -- doesn't have this failure mode, because it never asks the decoder
 * to jump to an arbitrary frame; it decodes every frame in presentation
 * order and simply skips converting the ones that fall between sample
 * points.
 *
 * The decoder is configured in byte-buffer mode (no output Surface) and
 * reads frames via [MediaCodec.getOutputImage]. An earlier attempt routed
 * output through a Surface backed by an [android.media.ImageReader], which
 * crashed on-device ("non-zero capacity for nullptr pointer" from
 * ImageReader's native plane creation) -- this device's hardware VP9
 * decoder doesn't populate ImageReader-consumed Surface buffers in a way
 * ImageReader can expose as YUV planes. Byte-buffer mode sidesteps the
 * Surface/ImageReader consumer path entirely.
 *
 * A Telegram video sticker keeps its transparency in a second VP9 bitstream
 * per frame, which [MediaExtractor] cannot reach -- see [WebmAlphaDemuxer].
 * Those files take [extractFramesWithAlpha]; everything else stays on the
 * MediaExtractor path. */
object VideoStickerConverter {
    private const val MAX_FRAMES = 120
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    suspend fun extractFrames(
        videoFile: File,
        targetPx: Int,
        maxDurationMs: Long = SizeBudget.MAX_TOTAL_DURATION_MS,
    ): List<TimedFrame>? {
        WebmAlphaDemuxer.readAlphaTrack(videoFile)?.let { track ->
            // A failure here falls through rather than giving up: an opaque
            // sticker beats no sticker, and the extractor path can still
            // decode the colour bitstream on its own.
            extractFramesWithAlpha(track, targetPx, maxDurationMs)?.let { return it }
        }
        return extractFramesViaExtractor(videoFile, targetPx, maxDurationMs)
    }

    private fun sampleIntervalUs(): Long = (1_000_000.0 / SizeBudget.TARGET_FPS)
        .coerceAtLeast(SizeBudget.MIN_FRAME_DURATION_MS * 1000.0)
        .toLong()

    /** Decodes the colour and alpha bitstreams separately and recombines
     * them, which is what WebM alpha requires: the addition is a full VP9
     * frame whose luma *is* the opacity mask.
     *
     * The two passes are sequential rather than concurrent because not every
     * device will hand out two hardware VP9 decoder instances at once. That
     * costs holding the sampled alpha planes in memory between passes --
     * one byte per pixel per sampled frame, so bounded by [MAX_FRAMES] times
     * the frame area. Doing it the other way round would be far worse, since
     * colour frames are four bytes a pixel.
     *
     * Which timestamps to sample is decided before either pass: the demuxer
     * already knows every frame's presentation time, so both passes agree on
     * the set without having to decode anything to find out. */
    private suspend fun extractFramesWithAlpha(
        track: WebmAlphaTrack,
        targetPx: Int,
        maxDurationMs: Long,
    ): List<TimedFrame>? {
        if (track.frames.any { it.alpha == null }) return null

        val interval = sampleIntervalUs()
        val maxDurationUs = maxDurationMs * 1000
        val wanted = LinkedHashSet<Long>()
        var lastSampledUs = -interval
        for (frame in track.frames) {
            if (frame.presentationTimeUs > maxDurationUs) break
            if (frame.presentationTimeUs - lastSampledUs < interval) continue
            wanted += frame.presentationTimeUs
            lastSampledUs = frame.presentationTimeUs
            if (wanted.size >= MAX_FRAMES) break
        }
        if (wanted.isEmpty()) return null

        val alphaPlanes = HashMap<Long, LumaPlane>(wanted.size)
        val alphaDecoded = decodeStream(
            track,
            track.frames.map { it.presentationTimeUs to it.alpha!! },
            wanted,
        ) { pts, image -> alphaPlanes[pts] = lumaPlane(image) }
        if (!alphaDecoded || alphaPlanes.isEmpty()) return null

        val collected = mutableListOf<TimedFrame>()
        val colourDecoded = decodeStream(
            track,
            track.frames.map { it.presentationTimeUs to it.colour },
            wanted,
        ) { pts, image ->
            val bitmap = imageToBitmap(image, alphaPlanes[pts])
            collected += TimedFrame(pts / 1000, BitmapPrep.centerCropSquareAndScale(bitmap, targetPx))
        }
        if (!colourDecoded) return null

        return collected.takeIf { it.isNotEmpty() }
    }

    /** Feeds one already-demuxed bitstream through a decoder, invoking
     * [onImage] for the outputs whose timestamp is in [wanted]. */
    private suspend fun decodeStream(
        track: WebmAlphaTrack,
        samples: List<Pair<Long, ByteArray>>,
        wanted: Set<Long>,
        onImage: (Long, Image) -> Unit,
    ): Boolean {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createVideoFormat(track.mime, track.width, track.height)
            track.codecPrivate?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            val decoder = MediaCodec.createDecoderByType(track.mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (next >= samples.size) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val (pts, payload) = samples[next++]
                            val buffer = decoder.getInputBuffer(inIndex) ?: return false
                            if (buffer.capacity() < payload.size) return false
                            buffer.clear()
                            buffer.put(payload)
                            decoder.queueInputBuffer(inIndex, 0, payload.size, pts, 0)
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    // getOutputImage() must be read before the buffer is
                    // released -- release invalidates it.
                    if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs in wanted) {
                        decoder.getOutputImage(outIndex)?.use { onImage(bufferInfo.presentationTimeUs, it) }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            releaseQuietly(codec)
        }
    }

    private suspend fun extractFramesViaExtractor(
        videoFile: File,
        targetPx: Int,
        maxDurationMs: Long,
    ): List<TimedFrame>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val interval = sampleIntervalUs()
            val maxDurationUs = maxDurationMs * 1000

            val collected = mutableListOf<TimedFrame>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastSampledUs = -interval

            while (!outputDone && collected.size < MAX_FRAMES) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val sampleSize = buffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    val ptsUs = bufferInfo.presentationTimeUs
                    val shouldSample = bufferInfo.size > 0 &&
                        ptsUs - lastSampledUs >= interval &&
                        ptsUs <= maxDurationUs
                    if (shouldSample) {
                        decoder.getOutputImage(outIndex)?.use { image ->
                            val bitmap = imageToBitmap(image, null)
                            collected += TimedFrame(
                                ptsUs / 1000,
                                BitmapPrep.centerCropSquareAndScale(bitmap, targetPx),
                            )
                            lastSampledUs = ptsUs
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                        ptsUs > maxDurationUs
                    ) {
                        outputDone = true
                    }
                }
            }

            collected.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            releaseQuietly(codec)
            extractor.release()
        }
    }

    private fun releaseQuietly(codec: MediaCodec?) {
        try {
            codec?.stop()
        } catch (_: Exception) {
            // Ignore -- may not have started successfully.
        }
        codec?.release()
    }

    /** A decoded alpha frame: one byte of opacity per pixel, which is what
     * the alpha bitstream's luma plane carries. */
    private class LumaPlane(val width: Int, val height: Int, val data: ByteArray) {
        fun at(col: Int, row: Int): Int = data[row * width + col].toInt() and 0xFF
    }

    /** Converts a YUV_420_888 [Image] to an ARGB [Bitmap], taking opacity
     * from [alpha] when the frame has a decoded alpha plane.
     *
     * This used to go through an NV21 + JPEG round-trip. That was the reason
     * transparency could never survive even once the alpha plane was
     * available: JPEG has no alpha channel, so every frame came out opaque no
     * matter what was composited into it. The conversion below is JFIF
     * full-range BT.601, which is what the JPEG round-trip was doing
     * implicitly, so colour output is unchanged.
     *
     * Reads through [Image.getCropRect] rather than [Image.getWidth]/
     * [Image.getHeight] directly, since decoders commonly pad the underlying
     * buffer beyond the actual visible frame. On-device testing traced a
     * real magenta/green corruption artifact further than that, though: to a
     * portrait Telegram video decoding at an *odd* width (333). Chroma is
     * subsampled 2:1 per axis, so an odd luma width leaves one column with
     * no well-defined chroma pair, and naively flooring `width / 2` shifts
     * every chroma sample after it half a column out of registration with
     * the luma plane it's supposed to color, for the rest of the frame. The
     * crop is rounded down to even to sidestep the case entirely -- losing
     * one edge pixel is imperceptible next to a whole frame of wrong color. */
    private fun imageToBitmap(image: Image, alpha: LumaPlane?): Bitmap {
        val crop = image.cropRect.takeIf { !it.isEmpty } ?: Rect(0, 0, image.width, image.height)
        val width = crop.width() and 1.inv()
        val height = crop.height() and 1.inv()
        val usableAlpha = alpha?.takeIf { it.width >= width && it.height >= height }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaLeft = crop.left / 2
        val chromaTop = crop.top / 2

        val pixels = IntArray(width * height)
        var out = 0
        for (row in 0 until height) {
            val yRow = (crop.top + row) * yPlane.rowStride
            val chromaRow = chromaTop + row / 2
            val uRow = chromaRow * uPlane.rowStride
            val vRow = chromaRow * vPlane.rowStride
            for (col in 0 until width) {
                val y = yBuffer.get(yRow + (crop.left + col) * yPlane.pixelStride).toInt() and 0xFF
                val chromaCol = chromaLeft + col / 2
                val u = (uBuffer.get(uRow + chromaCol * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vRow + chromaCol * vPlane.pixelStride).toInt() and 0xFF) - 128
                val r = y + ((1436 * v) shr 10)
                val g = y - ((352 * u + 731 * v) shr 10)
                val b = y + ((1815 * u) shr 10)
                val a = usableAlpha?.at(col, row) ?: 255
                pixels[out++] = (a shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun lumaPlane(image: Image): LumaPlane {
        val crop = image.cropRect.takeIf { !it.isEmpty } ?: Rect(0, 0, image.width, image.height)
        val width = crop.width() and 1.inv()
        val height = crop.height() and 1.inv()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(width * height)
        var pos = 0
        for (row in 0 until height) {
            val rowStart = (crop.top + row) * plane.rowStride
            for (col in 0 until width) {
                data[pos++] = buffer.get(rowStart + (crop.left + col) * plane.pixelStride)
            }
        }
        return LumaPlane(width, height, data)
    }

    private fun clamp(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value
}
