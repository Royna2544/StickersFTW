package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.royna.stickersftw.model.MediaCrop
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
    private const val TAG = "VideoStickerConverter"
    private const val MAX_FRAMES = 120
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    suspend fun extractFrames(
        videoFile: File,
        targetPx: Int,
        /** Exact window to decode after [startMs]. Callers cap a persisted
         * selection to their destination before it reaches this low-level
         * decoder. */
        maxDurationMs: Long = SizeBudget.MAX_TOTAL_DURATION_MS,
        /** Where in the clip the sticker starts. A sticker is at most
         * [SizeBudget.MAX_TOTAL_DURATION_MS] long, so anything longer than
         * that has to give up most of itself -- and the part worth keeping is
         * almost never the opening seconds, which is all this used to take. */
        startMs: Long = 0L,
        crop: MediaCrop? = null,
    ): List<TimedFrame>? = extractFrameSequence(
        videoFile,
        targetPx,
        maxDurationMs,
        startMs,
        crop,
    )?.frames

    suspend fun extractFrameSequence(
        videoFile: File,
        targetPx: Int,
        maxDurationMs: Long = SizeBudget.MAX_TOTAL_DURATION_MS,
        startMs: Long = 0L,
        crop: MediaCrop? = null,
    ): TimedFrameSequence? {
        WebmAlphaDemuxer.readAlphaTrack(videoFile)?.let { track ->
            // A failure here falls through rather than giving up: an opaque
            // sticker beats no sticker, and the extractor path can still
            // decode the colour bitstream on its own.
            extractFramesWithAlpha(track, targetPx, maxDurationMs, startMs, crop, MAX_FRAMES)?.let {
                return rebased(it)
            }
        }
        return extractFramesViaExtractor(videoFile, targetPx, maxDurationMs, startMs, crop, MAX_FRAMES)
            ?.let { rebased(it) }
    }

    /** Decodes one unscaled source frame for the crop editor. This uses the
     * same codec path as conversion, so rotation/aspect and sparse-keyframe
     * behaviour cannot disagree with the sticker that will be produced. */
    suspend fun extractPreviewFrame(videoFile: File, startMs: Long = 0L): Bitmap? {
        WebmAlphaDemuxer.readAlphaTrack(videoFile)?.let { track ->
            extractFramesWithAlpha(
                track,
                targetPx = null,
                maxDurationMs = SizeBudget.MAX_TOTAL_DURATION_MS,
                startMs = startMs,
                crop = null,
                maxFrames = 1,
            )?.frames?.firstOrNull()?.bitmap?.let { return it }
        }
        return extractFramesViaExtractor(
            videoFile,
            targetPx = null,
            maxDurationMs = SizeBudget.MAX_TOTAL_DURATION_MS,
            startMs = startMs,
            crop = null,
            maxFrames = 1,
        )?.frames?.firstOrNull()?.bitmap
    }

    /** Shifts the kept frames so the first one sits at zero.
     *
     * Subtracting the requested start is not enough on its own: no frame lands
     * exactly on the millisecond asked for, so a trimmed sticker came out with
     * its first frame a frame-interval late. Small enough not to see, but it
     * makes "the sticker starts where you chose" true only approximately, and
     * an invariant that is nearly true is one nothing downstream can rely on. */
    private fun rebased(sequence: TimedFrameSequence): TimedFrameSequence {
        val offset = sequence.frames.firstOrNull()?.timestampMs ?: return sequence
        if (offset == 0L) return sequence
        return sequence.copy(
            frames = sequence.frames.map { it.copy(timestampMs = it.timestampMs - offset) },
        )
    }

    /** How long [videoFile] runs, or null when it will not say.
     *
     * Used to decide whether the clip even needs trimming, so a failure here
     * means "do not offer a trim" rather than anything fatal. */
    fun durationMsOf(videoFile: File): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.let { it / 1000 }
                ?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** The gap between kept frames, for a source running [durationUs] long.
     *
     * Two things set it. The nominal target rate gets 10% of slack, because a
     * clip authored *at* the target drifts a hair either side of the exact
     * gap -- this pack's 30fps source has 33000us frames against a 33333us
     * target, and without slack every single frame lands early enough to be
     * rejected, so the sticker played at two thirds the rate for no benefit
     * at all. The slack is far smaller than the step to the next sensible
     * rate, so a genuinely faster source is still decimated.
     *
     * The second is [MAX_FRAMES]. Spreading that budget across the whole
     * duration matters because the sampling loop stops dead once it's full:
     * a long clip sampled too densely doesn't get a sparser version of
     * itself, it gets its first few seconds and nothing after. */
    private fun sampleIntervalUs(durationUs: Long): Long {
        val nominal = 1_000_000.0 / SizeBudget.TARGET_FPS * 0.9
        val spread = if (durationUs > 0) durationUs.toDouble() / MAX_FRAMES else 0.0
        return maxOf(nominal, spread)
            .coerceAtLeast(SizeBudget.MIN_FRAME_DURATION_MS * 1000.0)
            .toLong()
    }

    /** Decides which decoded frames to keep for [SizeBudget.TARGET_FPS].
     *
     * Advances a fixed-step clock along the source's own timeline rather than
     * measuring the gap since the last frame it kept. Measuring from the last
     * kept frame aliases badly whenever the source runs faster than the
     * target: a 30fps source never has two consecutive frames a full 50ms
     * apart, so every second frame was rejected and the sticker came out at
     * 15fps; a 25fps source collapsed to 12.5. Anchoring to the timeline lets
     * the kept frames land unevenly against each other while averaging out at
     * the target rate, which is the point. A source already at or below the
     * target still passes through whole. */
    private class FrameClock(private val intervalUs: Long) {
        private var nextTargetUs = 0L

        fun accept(ptsUs: Long): Boolean {
            if (ptsUs < nextTargetUs) return false
            do {
                nextTargetUs += intervalUs
            } while (nextTargetUs <= ptsUs)
            return true
        }
    }

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
        targetPx: Int?,
        maxDurationMs: Long,
        startMs: Long,
        crop: MediaCrop?,
        maxFrames: Int,
    ): TimedFrameSequence? {
        if (track.frames.any { it.alpha == null }) return null

        // Both bitstreams are decoded from the start regardless -- VP9 frames
        // depend on what came before, so there is nothing to skip to. The
        // window only decides which decoded frames are kept.
        val startUs = startMs * 1000
        val maxDurationUs = maxDurationMs * 1000
        val endUs = startUs + maxDurationUs
        val declaredUs = track.durationUs
            ?.minus(startUs)
            ?.takeIf { it > 0L }
        val representedUs = minOf(
            declaredUs ?: (track.frames.last().presentationTimeUs - startUs),
            maxDurationUs,
        )
        val clock = FrameClock(sampleIntervalUs(representedUs))
        val wanted = LinkedHashSet<Long>()
        for (frame in track.frames) {
            if (frame.presentationTimeUs < startUs) continue
            // The requested duration is an end-exclusive playback span. A
            // source frame exactly on the boundary belongs to the next clip,
            // otherwise the encoder must stretch the result by at least 1ms.
            if (frame.presentationTimeUs >= endUs) break
            if (!clock.accept(frame.presentationTimeUs - startUs)) continue
            wanted += frame.presentationTimeUs
            if (wanted.size >= maxFrames) break
        }
        if (wanted.isEmpty()) return null
        val intendedDurationMs = declaredUs
            ?.let { minOf(it, maxDurationUs) }
            ?.let { (it + 999L) / 1000L }
            ?: FrameSamplingPolicy.durationMs(wanted.map { (it - startUs) / 1000 })
            ?: return null

        val alphaPlanes = HashMap<Long, LumaPlane>(wanted.size)
        val alphaDecoded = decodeStream(
            track,
            track.frames.map { it.presentationTimeUs to it.alpha!! },
            wanted,
        ) { pts, image -> alphaPlanes[pts] = lumaPlane(image) }
        if (!alphaDecoded || alphaPlanes.isEmpty()) return null

        // A decoder is allowed to drop an output. Never turn that one missing
        // alpha plane into a fully opaque colour frame: transparent regions
        // would flash black/opaque for exactly that frame. Hold the previous
        // sampled frame a little longer by omitting unmatched timestamps.
        val matchedWanted = wanted.filterTo(LinkedHashSet()) { it in alphaPlanes }
        if (matchedWanted.isEmpty()) return null
        if (matchedWanted.size != wanted.size) {
            Log.w(
                TAG,
                "alpha decoder produced ${matchedWanted.size}/${wanted.size} sampled frames; " +
                    "dropping unmatched colour frames",
            )
        }

        val collected = mutableListOf<TimedFrame>()
        val colourDecoded = decodeStream(
            track,
            track.frames.map { it.presentationTimeUs to it.colour },
            matchedWanted,
        ) { pts, image ->
            val bitmap = imageToBitmap(image, requireNotNull(alphaPlanes[pts]))
            // Rebased so the sticker starts at zero however far in the window
            // begins; WebpAnimationEncoder reads these as frame timings.
            collected += TimedFrame((pts - startUs) / 1000, prepareFrame(bitmap, targetPx, crop))
        }
        if (!colourDecoded) return null

        return collected.takeIf { it.isNotEmpty() }
            ?.let { TimedFrameSequence(it, intendedDurationMs) }
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
            val decoder = createConfiguredDecoder(track.mime, format)
            codec = decoder
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
        } catch (e: Exception) {
            Log.w(TAG, "alpha-demuxed decode failed (${track.mime} ${track.width}x${track.height})", e)
            false
        } finally {
            releaseQuietly(codec)
        }
    }

    private suspend fun extractFramesViaExtractor(
        videoFile: File,
        targetPx: Int?,
        maxDurationMs: Long,
        startMs: Long,
        crop: MediaCrop?,
        maxFrames: Int,
    ): TimedFrameSequence? {
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

            val startUs = startMs * 1000
            if (startUs > 0) {
                // PREVIOUS_SYNC, not CLOSEST: the decoder needs a keyframe to
                // start from, and Telegram-style clips are sparsely keyframed,
                // so asking for the closest sample can land somewhere that
                // decodes to nothing. Starting a little early and dropping the
                // frames before the window costs a few decodes and always
                // works.
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val decoder = createConfiguredDecoder(mime, format)
            codec = decoder
            decoder.start()

            val maxDurationUs = maxDurationMs * 1000
            val endUs = startUs + maxDurationUs
            val declaredUs = format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.minus(startUs)
                ?.takeIf { it > 0L }
            val representedUs = minOf(declaredUs ?: maxDurationUs, maxDurationUs)
            val clock = FrameClock(sampleIntervalUs(representedUs))

            val collected = mutableListOf<TimedFrame>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var seenFrames = 0
            var firstPts = -1L
            var lastPts = -1L

            while (!outputDone && collected.size < maxFrames) {
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
                    if (bufferInfo.size > 0) {
                        seenFrames++
                        if (firstPts < 0) firstPts = ptsUs
                        lastPts = ptsUs
                    }
                    val shouldSample = bufferInfo.size > 0 &&
                        ptsUs >= startUs &&
                        ptsUs < endUs &&
                        clock.accept(ptsUs - startUs)
                    if (shouldSample) {
                        decoder.getOutputImage(outIndex)?.use { image ->
                            val bitmap = imageToBitmap(image, null)
                            collected += TimedFrame(
                                (ptsUs - startUs) / 1000,
                                prepareFrame(bitmap, targetPx, crop),
                            )
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                        ptsUs >= endUs
                    ) {
                        outputDone = true
                    }
                }
            }

            if (collected.isEmpty()) {
                Log.w(
                    TAG,
                    "extractor path decoded no frames from ${videoFile.name}: " +
                        "window ${startUs}..$endUs, saw $seenFrames frames " +
                        "spanning $firstPts..$lastPts",
                )
            }
            collected.takeIf { it.isNotEmpty() }?.let { frames ->
                val durationMs = declaredUs
                    ?.let { minOf(it, maxDurationUs) }
                    ?.let { (it + 999L) / 1000L }
                    ?: FrameSamplingPolicy.durationMs(frames.map { it.timestampMs })
                TimedFrameSequence(frames, durationMs)
            }
        } catch (e: Exception) {
            // A bare swallow here made every decoder problem surface as the
            // same unexplained "could not decode any usable frames", with
            // nothing in the log to say which sticker or why.
            Log.w(TAG, "extractor path failed on ${videoFile.name}", e)
            null
        } finally {
            releaseQuietly(codec)
            extractor.release()
        }
    }

    private fun prepareFrame(bitmap: Bitmap, targetPx: Int?, crop: MediaCrop?): Bitmap =
        if (targetPx == null) bitmap else BitmapPrep.cropAndFitSquare(bitmap, targetPx, crop)

    /** Picks a decoder for [mime] that will actually accept this frame size.
     *
     * Telegram video stickers are not always an even number of pixels tall --
     * 512x393 turned up in a real pack. Decoders that require 2-aligned
     * dimensions answer such a size from [MediaCodec.configure] with a bare
     * [IllegalArgumentException] carrying no message, which surfaced as an
     * unexplained "could not decode any usable frames" and one sticker
     * silently missing from the pack.
     *
     * The frame size is NOT rounded to make it fit. That was tried first and
     * is actively dangerous: configuring the emulator's VP9 decoder at 512x392
     * for a bitstream that says 393 aborts the process from native code
     * (SIGABRT in CodecLooper), taking a running conversion down with it. The
     * dimensions handed to configure have to match the bitstream, so the only
     * safe move is to find a decoder that takes them as they are -- devices
     * typically ship several for VP9, and the software one is far more liberal
     * about odd sizes than the hardware one.
     *
     * If nothing will take it, this throws and the caller records that single
     * sticker as failed, which the pack's warning then reports. */
    private fun createConfiguredDecoder(mime: String, format: MediaFormat): MediaCodec {
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)

        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            // Ask each one whether it can take this exact size, and try those
            // that say yes first. It is only a hint -- configure remains the
            // real test -- but it usually gets the right decoder on the first
            // attempt instead of after a rejection.
            .sortedByDescending { info ->
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height)
                }.getOrNull() ?: false
            }

        var lastFailure: Exception? = null
        for (info in candidates) {
            val decoder = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
            try {
                decoder.configure(format, null, null, 0)
                return decoder
            } catch (e: Exception) {
                releaseQuietly(decoder)
                lastFailure = e
                Log.w(TAG, "${info.name} rejected ${width}x$height for $mime")
            }
        }
        throw lastFailure ?: IllegalStateException("no $mime decoder accepts ${width}x$height")
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
