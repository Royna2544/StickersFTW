package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Decodes frames out of a video clip via a manual MediaExtractor+MediaCodec
 * pipeline -- used both to reduce a fetched .webm Telegram sticker to
 * WhatsApp-ready frames, and to source frames for a Telegram video-sticker
 * push from a locally picked clip.
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
 * Surface/ImageReader consumer path entirely. */
object VideoStickerConverter {
    private const val MAX_FRAMES = 120
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    suspend fun extractFrames(
        videoFile: File,
        targetPx: Int,
        maxDurationMs: Long = SizeBudget.MAX_TOTAL_DURATION_MS,
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

            val sampleIntervalUs = (1_000_000.0 / SizeBudget.TARGET_FPS)
                .coerceAtLeast(SizeBudget.MIN_FRAME_DURATION_MS * 1000.0)
                .toLong()
            val maxDurationUs = maxDurationMs * 1000

            val collected = mutableListOf<TimedFrame>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastSampledUs = -sampleIntervalUs

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
                        ptsUs - lastSampledUs >= sampleIntervalUs &&
                        ptsUs <= maxDurationUs
                    // getOutputImage() must be read before the buffer is
                    // released -- release invalidates it.
                    if (shouldSample) {
                        decoder.getOutputImage(outIndex)?.use { image ->
                            val bitmap = imageToBitmap(image)
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
            try {
                codec?.stop()
            } catch (_: Exception) {
                // Ignore -- may not have started successfully.
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Converts a YUV_420_888 [Image] to a [Bitmap] via an NV21 + JPEG
     * round-trip -- there's no direct public API for this conversion, and
     * this path is the standard, widely-used approach that correctly
     * handles both planar and semi-planar chroma layouts via row/pixel
     * stride. Quality loss from the intermediate JPEG is negligible next to
     * the lossy WebP re-encode every sticker goes through afterward. */
    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val nv21 = yuv420888ToNv21(image, width, height)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 100, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun yuv420888ToNv21(image: Image, width: Int, height: Int): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val idx = rowStart + col * uvPixelStride
                nv21[pos++] = vBuffer.get(idx)
                nv21[pos++] = uBuffer.get(idx)
            }
        }

        return nv21
    }
}
