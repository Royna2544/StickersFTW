package com.royna.stickersftw.conversion

import android.content.Context
import android.net.Uri
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoderOptions
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPMuxAnimParams
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Android has no public SDK API for encoding *animated* WebP (Bitmap.compress
 * only writes a single frame), so this wraps the com.aureusapps.android:webp-android
 * library's native WebPAnimEncoder. Animated-WebP quality can't be adjusted
 * after the fact, so each size-budget step below re-runs the full encode. */
object WebpAnimationEncoder {
    suspend fun encode(
        context: Context,
        frames: List<TimedFrame>,
        targetPx: Int,
        output: File,
        maxBytes: Int,
        minimizeSize: Boolean = true,
    ): ConversionOutcome {
        if (frames.isEmpty()) return ConversionOutcome.Failed("No frames to encode.")

        output.parentFile?.mkdirs()
        val totalDurationMs = (frames.last().timestampMs + frameIntervalEstimate(frames)).coerceAtLeast(1L)

        var lastSize = -1
        for (quality in SizeBudget.QUALITY_STEPS) {
            coroutineContext.ensureActive()

            val encoder = WebPAnimEncoder(
                context,
                targetPx,
                targetPx,
                WebPAnimEncoderOptions(
                    minimizeSize = minimizeSize,
                    animParams = WebPMuxAnimParams(loopCount = 0),
                ),
            )
            try {
                encoder.configure(
                    WebPConfig(lossless = WebPConfig.COMPRESSION_LOSSY, quality = quality.toFloat()),
                    WebPPreset.WEBP_PRESET_DEFAULT,
                )
                for (frame in frames) {
                    coroutineContext.ensureActive()
                    encoder.addFrame(frame.timestampMs, frame.bitmap)
                }
                if (output.exists()) output.delete()
                encoder.assemble(totalDurationMs, Uri.fromFile(output))
            } catch (e: Exception) {
                encoder.release()
                return ConversionOutcome.Failed(e.message ?: "Animated WebP encoding failed.")
            }
            encoder.release()

            val size = output.length().toInt()
            lastSize = size
            if (size in 1..maxBytes) {
                return ConversionOutcome.Success(size)
            }
        }

        return if (lastSize > 0) {
            ConversionOutcome.Success(
                lastSize,
                warning = "Animated sticker is ${lastSize / 1024}KB, over the ${maxBytes / 1024}KB budget.",
            )
        } else {
            ConversionOutcome.Failed("Animated WebP encoding produced no output.")
        }
    }

    private fun frameIntervalEstimate(frames: List<TimedFrame>): Long {
        if (frames.size < 2) return 100L
        return frames[1].timestampMs - frames[0].timestampMs
    }
}
