package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Canvas
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Renders a .tgs file (gzip-compressed Lottie JSON) to a bounded series of
 * bitmap frames by driving a LottieDrawable's progress directly and drawing
 * it to an offscreen canvas per sampled timestamp -- no View/Choreographer
 * involved, so this works from a plain background coroutine. */
object AnimatedStickerConverter {
    private const val MAX_FRAMES = 120

    suspend fun extractFrames(tgsFile: File, targetPx: Int): List<TimedFrame>? =
        extractFrameSequence(tgsFile, targetPx)?.frames

    suspend fun extractFrameSequence(tgsFile: File, targetPx: Int): TimedFrameSequence? {
        val json = try {
            GZIPInputStream(tgsFile.inputStream()).use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }

        val result = LottieCompositionFactory.fromJsonStringSync(String(json, Charsets.UTF_8), null)
        val composition = result.value ?: return null

        val drawable = LottieDrawable()
        drawable.composition = composition
        drawable.setBounds(0, 0, targetPx, targetPx)

        val durationMs = composition.duration.toLong().coerceIn(1L, SizeBudget.MAX_TOTAL_DURATION_MS)
        val fps = composition.frameRate.takeIf { it > 0f }?.toDouble() ?: 30.0
        val timestamps = FrameSamplingPolicy.sampleTimestampsMs(
            durationMs = durationMs,
            sourceFps = fps,
            maxFrames = MAX_FRAMES,
            minFrameDurationMs = SizeBudget.MIN_FRAME_DURATION_MS,
        ).takeIf { it.isNotEmpty() } ?: return null

        val frames = mutableListOf<TimedFrame>()
        for (timestampMs in timestamps) {
            coroutineContext.ensureActive()
            val progress = (timestampMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            drawable.progress = progress

            val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.draw(canvas)
            frames.add(TimedFrame(timestampMs, bitmap))
        }

        return frames.takeIf { it.isNotEmpty() }
            ?.let { TimedFrameSequence(it, durationMs) }
    }
}
