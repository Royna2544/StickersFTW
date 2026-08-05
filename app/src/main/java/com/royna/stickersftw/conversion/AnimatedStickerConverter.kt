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

    suspend fun extractFrames(tgsFile: File, targetPx: Int): List<TimedFrame>? {
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
        val fps = composition.frameRate.takeIf { it > 0f } ?: 30f
        val frameIntervalMs = (1000.0 / fps).coerceAtLeast(SizeBudget.MIN_FRAME_DURATION_MS.toDouble())
        val frameCount = (durationMs / frameIntervalMs).toInt().coerceIn(1, MAX_FRAMES)

        val frames = mutableListOf<TimedFrame>()
        for (i in 0 until frameCount) {
            coroutineContext.ensureActive()
            val timestampMs = (i * frameIntervalMs).toLong()
            val progress = (timestampMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            drawable.progress = progress

            val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.draw(canvas)
            frames.add(TimedFrame(timestampMs, bitmap))
        }

        return frames.takeIf { it.isNotEmpty() }
    }
}
