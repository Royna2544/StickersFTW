package com.royna.stickersftw.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.MediaCrop
import java.io.File

/** Orchestrates per-sticker conversion for both destinations. */
object StickerConversionPipeline {
    /** See [nudgedCopy] -- minimum brightness delta found on-device to
     * reliably survive re-encoding without collapsing back to a static
     * frame, plus a small margin. */
    private const val NUDGE_DELTA = 6

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
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val frames = framesFor(input, stickerType, SizeBudget.STICKER_PX, trimStartMs, crop)
            ?: return StickerConvertResult.Failed("Could not decode any usable frames.")

        val isAnimated = frames.size > 1
        val outcome = if (isAnimated) {
            WebpAnimationEncoder.encode(
                frames,
                SizeBudget.STICKER_PX,
                output,
                SizeBudget.ANIMATED_MAX_BYTES,
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
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()

        val frames = framesFor(input, stickerType, SizeBudget.STICKER_PX, trimStartMs, crop)
            ?: return StickerConvertResult.Failed("Could not decode any usable frames.")

        val outcome = if (forceAnimated) {
            val animatedFrames = if (frames.size > 1) {
                frames
            } else {
                val only = frames.first()
                listOf(only, TimedFrame(only.timestampMs + 100L, nudgedCopy(only.bitmap)))
            }
            // minimizeSize's near-duplicate frame elision is exactly what
            // collapses this padding back to a static image (see
            // nudgedCopy's doc) -- disabled only for this fallback path,
            // where the whole point is two frames that must stay separate.
            WebpAnimationEncoder.encode(
                animatedFrames,
                SizeBudget.STICKER_PX,
                output,
                SizeBudget.ANIMATED_MAX_BYTES,
                minimizeSize = false,
                bias = bias,
            )
        } else {
            StaticStickerConverter.compressWithBudget(frames.first().bitmap, output, SizeBudget.STATIC_MAX_BYTES)
        }

        return outcome.toResult(output, forceAnimated)
    }

    /** A byte-identical duplicate frame gets silently collapsed back into a
     * single-frame *simple*-format WebP by the animation encoder -- on-device
     * testing showed pack-consistency padding (a real frame + a copy)
     * producing a plain "VP8 " file with no ANIM chunk at all, defeating the
     * whole point of forcing that sticker to animate. A single-pixel tweak
     * isn't enough: lossy WEBP quantization at the lower quality steps this
     * pipeline falls back to under the size budget washes out a one-bit
     * difference just as effectively as a true duplicate.
     *
     * A ~2% zoom was tried next, but on-device it read as two distinct
     * pictures flickering (a 512px image shifts real content by several
     * pixels at a 2% crop, moving eyes/hair/edges enough to actually
     * register). A per-pixel checkerboard dither was tried after that, and
     * also got quantized away -- alternating +/-N noise is a *high-frequency*
     * pattern, which is precisely what block-transform lossy codecs (VP8
     * included) suppress most aggressively at any quality, since it's also
     * the same kind of noise human vision is least sensitive to.
     *
     * A uniform brightness shift across the whole frame is the opposite: a
     * pure low-frequency (DC) change, which transform-based codecs preserve
     * far more readily than high-frequency detail because removing it would
     * visibly gray out the entire block. On-device testing found a sharp,
     * consistent threshold at this quality/resolution: delta 4 collapses to
     * a static file just like the checkerboard did, delta 5 always survives
     * as a real two-frame animation. [NUDGE_DELTA] keeps a small margin
     * above that -- large enough to reliably survive re-encoding, but still
     * small enough to read as at most a barely-there flicker rather than the
     * visible bright/dim flash a larger shift (e.g. 12, tried first) produced
     * on real content. */
    private fun nudgedCopy(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val delta = NUDGE_DELTA
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) + delta
            val g = ((pixel shr 8) and 0xFF) + delta
            val b = (pixel and 0xFF) + delta
            pixels[i] = (pixel and 0xFF000000.toInt()) or
                (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or
                b.coerceIn(0, 255)
        }
        val copy = Bitmap.createBitmap(width, height, source.config ?: Bitmap.Config.ARGB_8888)
        copy.setPixels(pixels, 0, width, 0, 0, width, height)
        return copy
    }

    suspend fun buildTrayIcon(
        input: File,
        stickerType: StickerMediaType,
        output: File,
        trimStartMs: Long = 0L,
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val frame = firstFrameFor(input, stickerType, SizeBudget.TRAY_PX, trimStartMs, crop)
            ?: return StickerConvertResult.Failed("Could not build a tray icon.")
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
        crop: MediaCrop? = null,
    ): StickerConvertResult {
        output.parentFile?.mkdirs()
        val outcome = if (isVideo) {
            TelegramVideoConverter.convert(
                input,
                output,
                SizeBudget.STICKER_PX,
                SizeBudget.TELEGRAM_VIDEO_MAX_BYTES,
                trimStartMs,
                crop,
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
        crop: MediaCrop? = null,
    ): Bitmap? = when (type) {
        StickerMediaType.AnimatedLottie ->
            AnimatedStickerConverter.extractFrames(input, targetPx)?.firstOrNull()?.bitmap
        StickerMediaType.Video ->
            VideoStickerConverter.extractFrames(input, targetPx, startMs = trimStartMs, crop = crop)
                ?.firstOrNull()?.bitmap
        else -> BitmapFactory.decodeFile(input.absolutePath)?.let { BitmapPrep.crop(it, crop) }
    }

    private suspend fun framesFor(
        input: File,
        type: StickerMediaType,
        targetPx: Int,
        trimStartMs: Long = 0L,
        crop: MediaCrop? = null,
    ): List<TimedFrame>? = when (type) {
        // Lottie takes no offset: a .tgs is authored as a sticker already and
        // is inside the duration limit by construction, so there is nothing to
        // choose between.
        StickerMediaType.AnimatedLottie -> AnimatedStickerConverter.extractFrames(input, targetPx)
        StickerMediaType.Video -> VideoStickerConverter.extractFrames(
            input,
            targetPx,
            startMs = trimStartMs,
            crop = crop,
        )
        else -> {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return null
            listOf(TimedFrame(0L, BitmapPrep.cropAndFitSquare(bitmap, targetPx, crop)))
        }
    }

    private fun ConversionOutcome.toResult(output: File, isAnimated: Boolean = false): StickerConvertResult = when (this) {
        is ConversionOutcome.Success -> StickerConvertResult.Success(output.absolutePath, warning, isAnimated)
        is ConversionOutcome.Failed -> StickerConvertResult.Failed(reason)
    }
}
