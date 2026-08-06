package com.royna.stickersftw.conversion

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the actual on-device video frame extraction/conversion pipeline
 * against a short synthetic clip. This exists because the real bug this
 * guards against -- MediaMetadataRetriever silently returning the same
 * keyframe for every sampled timestamp on some hardware/codec combos,
 * producing a pack that WhatsApp then rejects as "animated" with only one
 * real frame -- only reproduces on a real device/emulator decoder, never in
 * a host-JVM unit test. */
@RunWith(AndroidJUnit4::class)
class VideoStickerConverterInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newTempVideoFile(): File =
        File.createTempFile("test_clip", ".mp4", context.cacheDir)

    /** Encodes [colors] as consecutive frames of a real H.264/mp4 clip via
     * MediaCodec + MediaMuxer -- exercised through the same MediaExtractor/
     * MediaMetadataRetriever decode path production video stickers go
     * through, just with content this test fully controls. */
    private fun encodeTestVideo(outputFile: File, colors: List<Int>, size: Int = 128) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size, size).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 5)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                }
            }
        }

        // A surface-input encoder timestamps each frame from the wall clock
        // at unlockCanvasAndPost() time, so posting them back-to-back with
        // no delay collapses them to near-identical presentation timestamps
        // -- the muxer then writes a degenerate ~0-duration track with
        // effectively one usable frame regardless of how many were posted.
        // Sleeping between frames gives each one real, distinct spacing.
        for (color in colors) {
            val canvas = inputSurface.lockCanvas(null)
            canvas.drawColor(color)
            inputSurface.unlockCanvasAndPost(canvas)
            Thread.sleep(FRAME_INTERVAL_MS)
            drain(endOfStream = false)
        }
        codec.signalEndOfInputStream()
        drain(endOfStream = true)

        codec.stop()
        codec.release()
        inputSurface.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    @Test
    fun extractFrames_multiColorClip_yieldsMultipleDistinctFrames() = runBlocking {
        val videoFile = newTempVideoFile()
        try {
            encodeTestVideo(videoFile, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))

            val frames = VideoStickerConverter.extractFrames(videoFile, targetPx = 96)

            assertNotNull("Frame extraction should succeed for a valid multi-frame clip", frames)
            assertTrue("Expected more than one extracted frame, got ${frames!!.size}", frames.size > 1)

            // Guards the exact regression this test exists for: every sampled
            // frame being pixel-identical (the "stuck on one keyframe" bug).
            val centerPixels = frames.map { it.bitmap.getPixel(it.bitmap.width / 2, it.bitmap.height / 2) }
            assertTrue(
                "All sampled frames had the same center pixel -- extraction is stuck on one frame",
                centerPixels.distinct().size > 1,
            )
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun convertForWhatsapp_multiColorClip_reportsAnimated() = runBlocking {
        val videoFile = newTempVideoFile()
        val outputFile = File.createTempFile("test_out", ".webp", context.cacheDir)
        try {
            encodeTestVideo(videoFile, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))

            val result = StickerConversionPipeline.convertForWhatsapp(
                context,
                videoFile,
                outputFile,
                StickerMediaType.Video,
            )

            val success = result as? StickerConvertResult.Success
            assertNotNull("Conversion should succeed for a valid multi-frame clip: $result", success)
            assertTrue("Pipeline should classify a genuinely multi-frame clip as animated", success!!.isAnimated)
            assertTrue("Converted output file should be non-empty", outputFile.length() > 0)
        } finally {
            videoFile.delete()
            outputFile.delete()
        }
    }

    private companion object {
        /** Milliseconds to hold each posted frame before draining -- gives
         * the surface-input encoder real, distinct presentation timestamps
         * instead of collapsing them together (see the comment above). */
        const val FRAME_INTERVAL_MS = 150L
    }
}
