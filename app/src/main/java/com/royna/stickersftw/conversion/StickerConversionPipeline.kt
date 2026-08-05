package com.royna.stickersftw.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Orchestrates per-sticker conversion for both destinations. Outlier
 * normalization (a sticker whose own type disagrees with the pack's overall
 * classification) is handled inline here: a stray static image inside an
 * animated pack becomes a trivial 1-frame animation; a stray animated/video
 * sticker inside a static pack is reduced to its first frame. */
object StickerConversionPipeline {
    suspend fun convertForWhatsapp(
        context: Context,
        input: File,
        output: File,
        stickerType: StickerMediaType,
        packIsAnimated: Boolean,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val outcome = if (!packIsAnimated) {
            val frame = firstFrameFor(input, stickerType, SizeBudget.STICKER_PX)
                ?: return StickerConvertResult.Failed("Could not decode a usable frame.")
            StaticStickerConverter.compressWithBudget(
                BitmapPrep.centerCropSquareAndScale(frame, SizeBudget.STICKER_PX),
                output,
                SizeBudget.STATIC_MAX_BYTES,
            )
        } else {
            val frames = framesFor(input, stickerType, SizeBudget.STICKER_PX)
                ?: return StickerConvertResult.Failed("Could not decode any usable frames.")
            WebpAnimationEncoder.encode(
                context,
                frames,
                SizeBudget.STICKER_PX,
                output,
                SizeBudget.ANIMATED_MAX_BYTES,
            )
        }

        return outcome.toResult(output)
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

    private fun ConversionOutcome.toResult(output: File): StickerConvertResult = when (this) {
        is ConversionOutcome.Success -> StickerConvertResult.Success(output.absolutePath, warning)
        is ConversionOutcome.Failed -> StickerConvertResult.Failed(reason)
    }
}
