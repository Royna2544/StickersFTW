package com.royna.stickersftw.conversion

import com.royna.stickersftw.model.ConversionBias
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Android has no public SDK API for encoding *animated* WebP (Bitmap.compress
 * only writes a single frame), so this uses the portable native
 * `stickerwebp_core` wrapper around libwebp's WebPAnimEncoder. Animated-WebP
 * quality can't be adjusted after the fact, so each size-budget step below
 * re-runs the full encode. */
object WebpAnimationEncoder {
    /** Below this frame count, halving again would risk collapsing to a
     * single frame -- which flips the file back to plain "VP8 " static
     * format. Give up with a hard failure instead of shipping either
     * a broken-static or an over-budget file. */
    private const val MIN_FRAMES_FLOOR = 2

    /** libwebp's default effort level. */
    private const val ENCODE_METHOD = 4

    /** A final maximum-effort pass can rescue an animation that only just
     * misses its byte budget. It is deliberately gated to near misses: this
     * exact 512px VP9 repro was 510,056 bytes at method 4, then 487,954 at
     * method 6. Without this pass it fell through to another 2:1 frame cut,
     * turning a 30fps source into a visibly coarse 7.5fps WebP. */
    private const val FINAL_FIT_METHOD = 6
    private const val FINAL_FIT_MARGIN_PERCENT = 10

    /** A slow visible pulse is especially conspicuous, but very short frame
     * durations are rejected or clamped by WebP viewers. 100ms is already the
     * proven validator-compatible duration used by the old fallback; direct
     * muxing now makes its actual value visually irrelevant. */
    private const val STRUCTURAL_FRAME_DURATION_MS = 100

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

            val ladder = qualityLadder(bias)
            for (quality in ladder) {
                coroutineContext.ensureActive()
                val bytes = try {
                    encodeOnce(
                        currentFrames,
                        targetPx,
                        totalDurationMs.toInt(),
                        minimizeSize,
                        quality,
                        ENCODE_METHOD,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return ConversionOutcome.Failed(e.message ?: "Animated WebP encoding failed.")
                }

                output.writeBytes(bytes)
                val size = bytes.size
                lastSize = size
                if (size in 1..maxBytes) {
                    return ConversionOutcome.Success(size)
                }
            }

            // Spending CPU is preferable to throwing away half the remaining
            // frames when the ordinary pass only narrowly missed the cap.
            // Far-over-budget files skip this expensive attempt because they
            // need the much larger frame-count lever regardless.
            if (
                EncodingBudgetPolicy.shouldRetryAtHigherEffort(
                    lastSize,
                    maxBytes,
                    FINAL_FIT_MARGIN_PERCENT,
                )
            ) {
                val quality = ladder.last()
                val bytes = try {
                    encodeOnce(
                        currentFrames,
                        targetPx,
                        totalDurationMs.toInt(),
                        minimizeSize,
                        quality,
                        FINAL_FIT_METHOD,
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (bytes != null) {
                    output.writeBytes(bytes)
                    lastSize = bytes.size
                    if (bytes.size in 1..maxBytes) {
                        return ConversionOutcome.Success(bytes.size)
                    }
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

    /** Turns one still frame into a structurally animated WebP without
     * changing a single decoded pixel between its two frames.
     *
     * This intentionally does not call [encode]: WebPAnimEncoder recognizes
     * identical frames and collapses them back to a simple still. The native
     * core instead encodes the image once and directly muxes that same image
     * bitstream into two ANMF chunks. */
    suspend fun encodeRepeatedFrame(
        frame: TimedFrame,
        targetPx: Int,
        output: File,
        maxBytes: Int,
        bias: ConversionBias = ConversionBias.Auto,
    ): ConversionOutcome {
        if (frame.bitmap.width != targetPx || frame.bitmap.height != targetPx) {
            return ConversionOutcome.Failed(
                "Repeated frame must be ${targetPx}x$targetPx, got " +
                    "${frame.bitmap.width}x${frame.bitmap.height}.",
            )
        }
        output.parentFile?.mkdirs()
        var lastSize = -1
        for (quality in qualityLadder(bias)) {
            coroutineContext.ensureActive()
            val bytes = try {
                NativeWebpAnimEncoder.encodeRepeatedFrame(
                    bitmap = frame.bitmap,
                    frameDurationMs = STRUCTURAL_FRAME_DURATION_MS,
                    loopCount = 0,
                    quality = quality.toFloat(),
                    alphaQuality = alphaQualityFor(quality),
                    method = ENCODE_METHOD,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return ConversionOutcome.Failed(e.message ?: "Animated WebP encoding failed.")
            } ?: return ConversionOutcome.Failed("Could not mux the repeated-frame animation.")

            output.writeBytes(bytes)
            lastSize = bytes.size
            if (bytes.size in 1..maxBytes) return ConversionOutcome.Success(bytes.size)
        }
        return ConversionOutcome.Failed(
            "Repeated-frame WebP is ${lastSize / 1024}KB at lowest quality -- " +
                "exceeds the ${maxBytes / 1024}KB budget.",
        )
    }

    private suspend fun encodeOnce(
        frames: List<TimedFrame>,
        targetPx: Int,
        totalDurationMs: Int,
        minimizeSize: Boolean,
        quality: Int,
        method: Int,
    ): ByteArray {
        val encoder = NativeWebpAnimEncoder.create(
            width = targetPx,
            height = targetPx,
            loopCount = 0,
            minimizeSize = minimizeSize,
        ) ?: error("Could not start the WebP encoder.")

        return encoder.use {
            if (!it.configure(quality.toFloat(), alphaQualityFor(quality), method)) {
                error("Rejected WebP settings at quality $quality and method $method.")
            }
            for (frame in frames) {
                coroutineContext.ensureActive()
                if (!it.addFrame(frame.bitmap, frame.timestampMs.toInt())) {
                    error("Could not add a frame to the animation.")
                }
            }
            it.assemble(totalDurationMs) ?: error("Could not assemble the animation.")
        }
    }

    private fun frameIntervalEstimate(frames: List<TimedFrame>): Long {
        return FrameSamplingPolicy.estimateIntervalMs(frames.map { it.timestampMs })
    }
}
