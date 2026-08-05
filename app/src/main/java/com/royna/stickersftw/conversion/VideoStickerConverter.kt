package com.royna.stickersftw.conversion

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Samples frames out of a video clip via MediaMetadataRetriever (built
 * into the SDK, no extra dependency) -- used both to reduce a fetched .webm
 * Telegram sticker to WhatsApp-ready frames, and to source frames for a
 * Telegram video-sticker push from a locally picked clip. */
object VideoStickerConverter {
    private const val MAX_FRAMES = 120

    suspend fun extractFrames(
        videoFile: File,
        targetPx: Int,
        maxDurationMs: Long = SizeBudget.MAX_TOTAL_DURATION_MS,
    ): List<TimedFrame>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtMost(maxDurationMs)
                ?: return null
            if (durationMs <= 0L) return null

            val frameIntervalMs = (1000.0 / SizeBudget.TARGET_FPS)
                .coerceAtLeast(SizeBudget.MIN_FRAME_DURATION_MS.toDouble())
            val frameCount = (durationMs / frameIntervalMs).toInt().coerceIn(1, MAX_FRAMES)

            val frames = mutableListOf<TimedFrame>()
            for (i in 0 until frameCount) {
                coroutineContext.ensureActive()
                val timestampMs = (i * frameIntervalMs).toLong()
                val frame = retriever.getFrameAtTime(
                    timestampMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                frames.add(TimedFrame(timestampMs, BitmapPrep.centerCropSquareAndScale(frame, targetPx)))
            }
            frames.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
