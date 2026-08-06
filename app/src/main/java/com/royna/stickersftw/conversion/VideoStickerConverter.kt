package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Samples frames out of a video clip via MediaMetadataRetriever (built
 * into the SDK, no extra dependency) -- used both to reduce a fetched .webm
 * Telegram sticker to WhatsApp-ready frames, and to source frames for a
 * Telegram video-sticker push from a locally picked clip.
 *
 * Telegram's video stickers are short, sparsely-keyframed WebM clips, and
 * [MediaMetadataRetriever.getFrameAtTime] (timestamp-seek) reliably decodes
 * only the nearest *keyframe* on many devices -- for a clip with one
 * keyframe, every seek in a sampling loop silently returns that same frame,
 * producing a pack that looks static despite claiming to be animated. On
 * API 28+, [MediaMetadataRetriever.getFrameAtIndex] decodes by sequential
 * frame index instead of an approximate seek, which doesn't have this
 * failure mode. Frame count metadata is itself unreliable for this content,
 * so frames are discovered empirically by indexing until it fails rather
 * than trusting METADATA_KEY_VIDEO_FRAME_COUNT; timestamps are then spread
 * evenly across the real (metadata) duration. Older devices, or a source
 * that genuinely only yields one distinct frame either way, fall back to
 * the timestamp-seek loop. */
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
            val sampleCount = (durationMs / frameIntervalMs).toInt().coerceIn(1, MAX_FRAMES)

            val indexFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                extractByFrameIndex(retriever, durationMs, sampleCount)
            } else {
                emptyList()
            }
            // Sequential indexing is the reliable signal for "how many real
            // frames does this clip have" -- even a correctly-detected count
            // of 1 must be trusted as-is. Falling back to the timestamp-seek
            // loop here would spuriously inflate that count: it always
            // returns close to sampleCount frames as long as getFrameAtTime
            // keeps succeeding, even when every call lands on the same
            // keyframe -- reintroducing the false-"animated" bug this
            // index-based path exists to avoid. Only fall back when indexing
            // itself found nothing (API<28, or the source defeated it).
            val rawFrames = indexFrames.ifEmpty { extractByTimestampSeek(retriever, sampleCount, frameIntervalMs) }

            rawFrames.map { TimedFrame(it.first, BitmapPrep.centerCropSquareAndScale(it.second, targetPx)) }
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Discovers real frames by sequential index (not trusting frame-count
     * metadata, which is itself unreliable for this content) until
     * [MediaMetadataRetriever.getFrameAtIndex] fails, then spreads their
     * timestamps evenly across the known real duration. */
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun extractByFrameIndex(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        sampleCount: Int,
    ): List<Pair<Long, Bitmap>> {
        val bitmaps = mutableListOf<Bitmap>()
        var frameIndex = 0
        while (bitmaps.size < sampleCount) {
            coroutineContext.ensureActive()
            val frame = try {
                retriever.getFrameAtIndex(frameIndex)
            } catch (_: Exception) {
                null
            } ?: break
            bitmaps.add(frame)
            frameIndex++
        }
        if (bitmaps.isEmpty()) return emptyList()
        return bitmaps.mapIndexed { i, bitmap ->
            val timestampMs = if (bitmaps.size == 1) 0L else (i.toLong() * durationMs) / bitmaps.size
            timestampMs to bitmap
        }
    }

    private suspend fun extractByTimestampSeek(
        retriever: MediaMetadataRetriever,
        sampleCount: Int,
        frameIntervalMs: Double,
    ): List<Pair<Long, Bitmap>> {
        val frames = mutableListOf<Pair<Long, Bitmap>>()
        for (i in 0 until sampleCount) {
            coroutineContext.ensureActive()
            val timestampMs = (i * frameIntervalMs).toLong()
            val frame = retriever.getFrameAtTime(
                timestampMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: continue
            frames.add(timestampMs to frame)
        }
        return frames
    }
}
