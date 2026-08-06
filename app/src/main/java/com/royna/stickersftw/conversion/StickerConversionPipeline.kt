package com.royna.stickersftw.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Orchestrates per-sticker conversion for both destinations. */
object StickerConversionPipeline {
    /** Whether a sticker actually ends up animated is decided here, per
     * sticker, by how many usable frames extraction genuinely produces --
     * never by the source's nominal format. Telegram video stickers are
     * short, sparsely-keyframed clips that some devices can only decode a
     * single (keyframe) frame from via the SDK's convenience extraction
     * APIs; encoding that single frame as a 1-frame "animated" WebP while
     * declaring the pack animated is exactly the mismatch that makes
     * WhatsApp reject the whole pack, so a single-frame result is encoded
     * as a plain static sticker instead. */
    suspend fun convertForWhatsapp(
        context: Context,
        input: File,
        output: File,
        stickerType: StickerMediaType,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val frames = framesFor(input, stickerType, SizeBudget.STICKER_PX)
            ?: return StickerConvertResult.Failed("Could not decode any usable frames.")

        val isAnimated = frames.size > 1
        val outcome = if (isAnimated) {
            WebpAnimationEncoder.encode(
                context,
                frames,
                SizeBudget.STICKER_PX,
                output,
                SizeBudget.ANIMATED_MAX_BYTES,
            )
        } else {
            StaticStickerConverter.compressWithBudget(frames.first().bitmap, output, SizeBudget.STATIC_MAX_BYTES)
        }

        return outcome.toResult(output, isAnimated)
    }

    /** Re-converts one sticker to match a pack-wide animated/static decision
     * made *after* seeing every sticker's own outcome (see the caller in
     * StickerPackRepository). WhatsApp's validator is all-or-nothing per
     * pack: if the pack is declared animated, every single sticker's WebP
     * must have more than one frame, and vice versa -- a pack with even one
     * mismatched sticker is rejected outright, not just that sticker. When
     * [forceAnimated] doesn't match what extraction actually produced, this
     * pads a single frame into a minimal 2-frame loop (animated pack, only
     * 1 real frame available) or drops to just the first frame (static
     * pack, source was genuinely multi-frame). */
    suspend fun convertForWhatsappForced(
        context: Context,
        input: File,
        output: File,
        stickerType: StickerMediaType,
        forceAnimated: Boolean,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val frames = framesFor(input, stickerType, SizeBudget.STICKER_PX)
            ?: return StickerConvertResult.Failed("Could not decode any usable frames.")

        val outcome = if (forceAnimated) {
            val animatedFrames = if (frames.size > 1) {
                frames
            } else {
                val only = frames.first()
                listOf(only, only.copy(timestampMs = only.timestampMs + 100L))
            }
            WebpAnimationEncoder.encode(context, animatedFrames, SizeBudget.STICKER_PX, output, SizeBudget.ANIMATED_MAX_BYTES)
        } else {
            StaticStickerConverter.compressWithBudget(frames.first().bitmap, output, SizeBudget.STATIC_MAX_BYTES)
        }

        return outcome.toResult(output, forceAnimated)
    }

    suspend fun buildTrayIcon(
        input: File,
        stickerType: StickerMediaType,
        output: File,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val frame = firstFrameFor(input, stickerType, SizeBudget.TRAY_PX)
            ?: return StickerConvertResult.Failed("Could not build a tray icon.")
        val outcome = StaticStickerConverter.compressWithBudget(
            BitmapPrep.centerCropSquareAndScale(frame, SizeBudget.TRAY_PX),
            output,
            SizeBudget.TRAY_MAX_BYTES,
        )
        return outcome.toResult(output)
    }

    /** Converts one locally-picked media item for a Telegram push. */
    suspend fun convertForTelegram(
        input: File,
        output: File,
        isVideo: Boolean,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val outcome = if (isVideo) {
            TelegramVideoConverter.convert(
                input,
                output,
                SizeBudget.STICKER_PX,
                SizeBudget.TELEGRAM_VIDEO_MAX_BYTES,
            )
        } else {
            StaticStickerConverter.convertAspectPreserving(
                input,
                output,
                SizeBudget.STICKER_PX,
                SizeBudget.TELEGRAM_STATIC_MAX_BYTES,
            )
        }
        return outcome.toResult(output)
    }

    private suspend fun firstFrameFor(
        input: File,
        type: StickerMediaType,
        targetPx: Int,
    ): Bitmap? = when (type) {
        StickerMediaType.AnimatedLottie ->
            AnimatedStickerConverter.extractFrames(input, targetPx)?.firstOrNull()?.bitmap
        StickerMediaType.Video ->
            VideoStickerConverter.extractFrames(input, targetPx)?.firstOrNull()?.bitmap
        else -> BitmapFactory.decodeFile(input.absolutePath)
    }

    private suspend fun framesFor(
        input: File,
        type: StickerMediaType,
        targetPx: Int,
    ): List<TimedFrame>? = when (type) {
        StickerMediaType.AnimatedLottie -> AnimatedStickerConverter.extractFrames(input, targetPx)
        StickerMediaType.Video -> VideoStickerConverter.extractFrames(input, targetPx)
        else -> {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return null
            listOf(TimedFrame(0L, BitmapPrep.centerCropSquareAndScale(bitmap, targetPx)))
        }
    }

    private fun ConversionOutcome.toResult(output: File, isAnimated: Boolean = false): StickerConvertResult = when (this) {
        is ConversionOutcome.Success -> StickerConvertResult.Success(output.absolutePath, warning, isAnimated)
        is ConversionOutcome.Failed -> StickerConvertResult.Failed(reason)
    }
}
