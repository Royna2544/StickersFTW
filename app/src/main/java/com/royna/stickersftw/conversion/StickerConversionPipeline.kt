package com.royna.stickersftw.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.MediaCrop
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
        bias: ConversionBias = ConversionBias.Auto,
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val sequence = framesFor(
            input,
            stickerType,
            SizeBudget.STICKER_PX,
            trimStartMs,
            trimDurationMs,
            crop,
        ) ?: return StickerConvertResult.Failed("Could not decode any usable frames.")
        val frames = sequence.frames

        val isAnimated = frames.size > 1
        val outcome = if (isAnimated) {
            WebpAnimationEncoder.encode(
                frames,
                SizeBudget.STICKER_PX,
                output,
                SizeBudget.ANIMATED_MAX_BYTES,
                totalDurationMs = sequence.durationMs,
                bias = bias,
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
        bias: ConversionBias = ConversionBias.Auto,
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val sequence = framesFor(
            input,
            stickerType,
            SizeBudget.STICKER_PX,
            trimStartMs,
            trimDurationMs,
            crop,
        ) ?: return StickerConvertResult.Failed("Could not decode any usable frames.")
        val frames = sequence.frames

        val outcome = if (forceAnimated) {
            if (frames.size > 1) {
                WebpAnimationEncoder.encode(
                    frames,
                    SizeBudget.STICKER_PX,
                    output,
                    SizeBudget.ANIMATED_MAX_BYTES,
                    totalDurationMs = sequence.durationMs,
                    bias = bias,
                )
            } else {
                WebpAnimationEncoder.encodeRepeatedFrame(
                    frames.first(),
                    SizeBudget.STICKER_PX,
                    output,
                    SizeBudget.ANIMATED_MAX_BYTES,
                    bias = bias,
                )
            }
        } else {
            StaticStickerConverter.compressWithBudget(frames.first().bitmap, output, SizeBudget.STATIC_MAX_BYTES)
        }

        return outcome.toResult(output, forceAnimated)
    }

    suspend fun buildTrayIcon(
        input: File,
        stickerType: StickerMediaType,
        output: File,
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val frame = firstFrameFor(
            input,
            stickerType,
            SizeBudget.TRAY_PX,
            trimStartMs,
            trimDurationMs,
            crop,
        ) ?: return StickerConvertResult.Failed("Could not build a tray icon.")
        val outcome = StaticStickerConverter.compressWithBudget(
            BitmapPrep.fitSquareWithPadding(frame, SizeBudget.TRAY_PX),
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
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val outcome = if (isVideo) {
            TelegramVideoConverter.convert(
                input,
                output,
                SizeBudget.STICKER_PX,
                SizeBudget.TELEGRAM_VIDEO_MAX_BYTES,
                trimStartMs = trimStartMs,
                trimDurationMs = trimDurationMs,
                crop = crop,
            )
        } else {
            StaticStickerConverter.convertAspectPreserving(
                input,
                output,
                SizeBudget.STICKER_PX,
                SizeBudget.TELEGRAM_STATIC_MAX_BYTES,
                crop,
            )
        }
        return outcome.toResult(output)
    }

    private suspend fun firstFrameFor(
        input: File,
        type: StickerMediaType,
        targetPx: Int,
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): Bitmap? = when (type) {
        StickerMediaType.AnimatedLottie ->
            AnimatedStickerConverter.extractFrames(input, targetPx)?.firstOrNull()?.bitmap
        StickerMediaType.Video ->
            VideoStickerConverter.extractFrames(
                input,
                targetPx,
                maxDurationMs = effectiveTrimDurationMs(
                    trimDurationMs,
                    SizeBudget.MAX_TOTAL_DURATION_MS,
                ),
                startMs = trimStartMs,
                crop = crop,
            )
                ?.firstOrNull()?.bitmap
        else -> BitmapFactory.decodeFile(input.absolutePath)?.let { BitmapPrep.crop(it, crop) }
    }

    private suspend fun framesFor(
        input: File,
        type: StickerMediaType,
        targetPx: Int,
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        crop: MediaCrop? = null,
    ): TimedFrameSequence? = when (type) {
        // Lottie takes no offset: a .tgs is authored as a sticker already and
        // is inside the duration limit by construction, so there is nothing to
        // choose between.
        StickerMediaType.AnimatedLottie -> AnimatedStickerConverter.extractFrameSequence(input, targetPx)
        StickerMediaType.Video -> VideoStickerConverter.extractFrameSequence(
            input,
            targetPx,
            maxDurationMs = effectiveTrimDurationMs(
                trimDurationMs,
                SizeBudget.MAX_TOTAL_DURATION_MS,
            ),
            startMs = trimStartMs,
            crop = crop,
        )
        else -> {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return null
            TimedFrameSequence(
                listOf(TimedFrame(0L, BitmapPrep.cropAndFitSquare(bitmap, targetPx, crop))),
            )
        }
    }

    private fun ConversionOutcome.toResult(output: File, isAnimated: Boolean = false): StickerConvertResult = when (this) {
        is ConversionOutcome.Success -> StickerConvertResult.Success(output.absolutePath, warning, isAnimated)
        is ConversionOutcome.Failed -> StickerConvertResult.Failed(reason)
    }
}
