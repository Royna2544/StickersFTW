package com.royna.stickersftw.conversion

import com.royna.stickersftw.model.ConversionBias
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Android has no public SDK API for encoding *animated* WebP (Bitmap.compress
 * only writes a single frame), so this wraps the com.aureusapps.android:webp-android
 * library's native WebPAnimEncoder. Animated-WebP quality can't be adjusted
 * after the fact, so each size-budget step below re-runs the full encode. */
object WebpAnimationEncoder {
    /** Below this frame count, halving again would risk collapsing to a
     * single frame -- which flips the file back to plain "VP8 " static
     * format (see StickerConversionPipeline's nudgedCopy doc for the same
     * failure mode). Give up with a hard failure instead of shipping either
     * a broken-static or an over-budget file. */
    private const val MIN_FRAMES_FLOOR = 2

    /** libwebp's default effort level. Higher spends more CPU for a smaller
     * file; the size ladder here already has cheaper levers to pull. */
    private const val ENCODE_METHOD = 4

    /** libwebp leaves the alpha channel lossless by default, which is fine
     * until a sticker actually has one. Once Telegram video stickers started
     * keeping their transparency, the alpha plane dominated the file: walking
     * colour quality all the way down from 80 to 20 barely moved the total,
     * so every step of the ladder overshot the budget and the encoder fell
     * through to halving the frame count -- a transparent sticker came out at
     * half the frame rate of the opaque one it replaced.
     *
     * Alpha is a cutout mask rather than picture detail, so it degrades far
     * more gracefully than colour and can descend alongside it. Kept above
     * the colour step so edges stay cleaner than the fill, and left lossless
     * at the top step so a sticker that already fits is encoded exactly as
     * before. */
    private fun alphaQualityFor(quality: Int): Int = (quality + 20).coerceIn(0, 100)

    /** The bias is expressed entirely as how far the quality ladder is
     * allowed to fall before the encoder gives up and halves the frame count
     * instead. Everything is tried at the full frame count first, so a longer
     * ladder means frames survive at lower quality and a shorter one means
     * the encoder reaches for the frame cut sooner.
     *
     * [ConversionBias.Smoothness] costs real time: two extra encode passes
     * over the whole animation before the first frame is ever dropped. */
    private fun qualityLadder(bias: ConversionBias): IntArray = when (bias) {
        ConversionBias.Sharpness -> intArrayOf(80, 65)
        ConversionBias.Auto -> SizeBudget.QUALITY_STEPS
        ConversionBias.Smoothness -> intArrayOf(80, 65, 50, 35, 20, 12, 8)
    }

    suspend fun encode(
        frames: List<TimedFrame>,
        targetPx: Int,
        output: File,
        maxBytes: Int,
        minimizeSize: Boolean = true,
        bias: ConversionBias = ConversionBias.Auto,
    ): ConversionOutcome {
        if (frames.isEmpty()) return ConversionOutcome.Failed("No frames to encode.")

        output.parentFile?.mkdirs()

        var currentFrames = frames
        var lastSize = -1
        while (true) {
            coroutineContext.ensureActive()
            val totalDurationMs = (currentFrames.last().timestampMs + frameIntervalEstimate(currentFrames)).coerceAtLeast(1L)

            for (quality in qualityLadder(bias)) {
                coroutineContext.ensureActive()

                val encoder = NativeWebpAnimEncoder.create(
                    width = targetPx,
                    height = targetPx,
                    loopCount = 0,
                    minimizeSize = minimizeSize,
                ) ?: return ConversionOutcome.Failed("Could not start the WebP encoder.")

                val bytes = try {
                    encoder.use {
                        if (!it.configure(quality.toFloat(), alphaQualityFor(quality), ENCODE_METHOD)) {
                            return ConversionOutcome.Failed("Rejected WebP settings at quality $quality.")
                        }
                        for (frame in currentFrames) {
                            coroutineContext.ensureActive()
                            if (!it.addFrame(frame.bitmap, frame.timestampMs.toInt())) {
                                return ConversionOutcome.Failed("Could not add a frame to the animation.")
                            }
                        }
                        it.assemble(totalDurationMs.toInt())
                    }
                } catch (e: Exception) {
                    return ConversionOutcome.Failed(e.message ?: "Animated WebP encoding failed.")
                } ?: return ConversionOutcome.Failed("Could not assemble the animation.")

                output.writeBytes(bytes)

                val size = output.length().toInt()
                lastSize = size
                if (size in 1..maxBytes) {
                    return ConversionOutcome.Success(size)
                }
            }

            // Lowest quality still doesn't fit -- WhatsApp rejects the whole
            // pack over a single oversized sticker, so shipping this anyway
            // (as a prior version of this code did, via a warning) is worse
            // than dropping the sticker. Halve the frame count (keeping
            // every other frame, so total playback duration is preserved
            // via the same timestamps) and retry the whole quality ladder --
            // fewer frames is a far bigger size lever than quality at the
            // low end.
            if (currentFrames.size <= MIN_FRAMES_FLOOR) {
                return ConversionOutcome.Failed(
                    "Animated WebP still ${lastSize / 1024}KB at ${currentFrames.size} frames and lowest " +
                        "quality -- exceeds the ${maxBytes / 1024}KB budget.",
                )
            }
            currentFrames = currentFrames.filterIndexed { index, _ -> index % 2 == 0 }
        }
    }

    private fun frameIntervalEstimate(frames: List<TimedFrame>): Long {
        if (frames.size < 2) return 100L
        return frames[1].timestampMs - frames[0].timestampMs
    }
}
