package com.royna.stickersftw.conversion

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Encodes a short video clip into Telegram's video-sticker format (VP8 in a
 * WEBM container, no audio) using MediaCodec's Surface-input encoding path
 * and MediaMuxer -- both SDK-only, no extra dependency. This is the riskiest
 * piece of the whole conversion pipeline (hand-rolled encode/drain loop,
 * device-dependent VP8 encoder availability): any failure here is caught and
 * surfaced as a per-sticker [ConversionOutcome.Failed] rather than crashing
 * the rest of the pack, per design. */
object TelegramVideoConverter {
    private class MuxerState {
        var trackIndex = -1
        var started = false
    }

    suspend fun convert(input: File, output: File, targetPx: Int, maxBytes: Int): ConversionOutcome =
        withContext(Dispatchers.Default) {
            val frames = VideoStickerConverter.extractFrames(
                input,
                targetPx,
                SizeBudget.TELEGRAM_MAX_DURATION_MS,
            ) ?: return@withContext ConversionOutcome.Failed("Could not decode video frames.")

            try {
                for (bitrate in BITRATE_STEPS_BPS) {
                    encodeAttempt(frames, targetPx, targetPx, output, bitrate)
                    val size = output.length().toInt()
                    if (size in 1..maxBytes) {
                        return@withContext ConversionOutcome.Success(size)
                    }
                    if (bitrate == BITRATE_STEPS_BPS.last()) {
                        return@withContext ConversionOutcome.Success(
                            size,
                            warning = "Video sticker is ${size / 1024}KB, over the ${maxBytes / 1024}KB budget.",
                        )
                    }
                }
                ConversionOutcome.Failed("Video encoding failed.")
            } catch (e: Exception) {
                ConversionOutcome.Failed(e.message ?: "Video encoding failed on this device.")
            }
        }

    private fun encodeAttempt(frames: List<TimedFrame>, width: Int, height: Int, output: File, bitrateBps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, SizeBudget.TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP8)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        val bufferInfo = MediaCodec.BufferInfo()
        val state = MuxerState()

        try {
            val frameDurationMs = 1000L / SizeBudget.TARGET_FPS
            for (frame in frames) {
                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(codec, muxer, bufferInfo, state, endOfStream = false)
                Thread.sleep(frameDurationMs)
            }
            drainEncoder(codec, muxer, bufferInfo, state, endOfStream = true)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best-effort teardown -- the encode either already
                // succeeded or already failed by this point.
            }
            codec.release()
            inputSurface.release()
            if (state.started) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                    // Same as above.
                }
            }
            muxer.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        state: MuxerState,
        endOfStream: Boolean,
    ) {
        if (endOfStream) {
            codec.signalEndOfInputStream()
        }
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    state.trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.started = true
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)
                        ?: throw IllegalStateException("Encoder output buffer was null.")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && state.started) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(state.trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private val BITRATE_STEPS_BPS = intArrayOf(500_000, 350_000, 220_000, 130_000)
}
