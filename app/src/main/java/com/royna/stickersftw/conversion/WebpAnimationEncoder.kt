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

    /** How many extra encodes the frame-count recovery may spend. Three
     * probes close most of a 2:1 gap; each one is a full encode of the whole
     * animation, and only runs when halving actually happened. */
    private const val FRAME_RECOVERY_PROBES = 3

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
        /** Intended playback duration before decoder drops or size-budget
         * frame decimation. Null retains legacy timestamp inference. */
        totalDurationMs: Long? = null,
        bias: ConversionBias = ConversionBias.Auto,
    ): ConversionOutcome {
        if (frames.isEmpty()) return ConversionOutcome.Failed("No frames to encode.")

        output.parentFile?.mkdirs()

        // Resolve the endpoint once from the complete extracted timeline.
        // Recomputing it after every 2:1 frame cut changes playback speed:
        // 0/33/66/99/132 becomes 0/66/132 and would otherwise infer a 198ms
        // end instead of preserving the original 165ms span.
        val endTimestampMs = FrameSamplingPolicy.endTimestampMs(
            frames.map { it.timestampMs },
            totalDurationMs,
        )?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            ?: return ConversionOutcome.Failed("Could not determine animation duration.")

        // Deliberately after the endpoint above, which is resolved from the
        // complete timeline: dropping a frame that shares an instant with its
        // neighbour must not shorten the animation.
        //
        // libwebp derives each frame's on-screen duration from the gap to the
        // next timestamp, so two frames on the same millisecond leave the
        // first lasting 0ms -- encoded, paid for in bytes, never shown, and
        // below the floor WhatsApp refuses a pack over.
        val sourceFrames = FrameSamplingPolicy.strictlyRisingIndices(frames.map { it.timestampMs })
            .map(frames::get)
        var currentFrames = sourceFrames
        var lastSize = -1
        // The smallest frame count already known not to fit at any quality.
        // Halving lands somewhere below it, and the gap between the two is
        // where the motion that need not have been dropped lives.
        var smallestFailingCount = Int.MAX_VALUE
        while (true) {
            coroutineContext.ensureActive()

            val ladder = qualityLadder(bias)
            for (quality in ladder) {
                coroutineContext.ensureActive()
                val bytes = try {
                    encodeOnce(
                        currentFrames,
                        targetPx,
                        endTimestampMs,
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
                    val recovered = recoverDroppedFrames(
                        sourceFrames,
                        currentFrames.size,
                        smallestFailingCount,
                        bytes,
                        targetPx,
                        endTimestampMs,
                        minimizeSize,
                        ladder,
                        maxBytes,
                    )
                    output.writeBytes(recovered)
                    return ConversionOutcome.Success(recovered.size)
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
                        endTimestampMs,
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
            // than dropping the sticker. Halve the frame count (distributing
            // retained frames across both visual endpoints) and retry the
            // whole quality ladder; the immutable end timestamp preserves
            // total playback duration --
            // fewer frames is a far bigger size lever than quality at the
            // low end.
            if (currentFrames.size <= MIN_FRAMES_FLOOR) {
                return ConversionOutcome.Failed(
                    "Animated WebP still ${lastSize / 1024}KB at ${currentFrames.size} frames and lowest " +
                        "quality -- exceeds the ${maxBytes / 1024}KB budget.",
                )
            }
            smallestFailingCount = currentFrames.size
            currentFrames = FrameSamplingPolicy.halfFrameIndices(currentFrames.size)
                .map(currentFrames::get)
        }
    }

    /** Wins back the frames halving threw away past the point it needed to.
     *
     * The frame-count lever is a 2:1 halving, so the count that finally fits
     * can sit far below the largest one that would have. One real 85-frame
     * sticker is the case: nothing fit at 85, so it halved to 43, which then
     * fit at the *top* quality with 497KB of a 500KB budget. Every count
     * between 44 and 84 was dismissed without being tried, and the frames in
     * that gap are the ones a viewer notices -- the sticker's motion is
     * concentrated in its second half, so uniform halving turned a shake into
     * a smooth drift.
     *
     * A few probes between the fitting count and the smallest failing one
     * recover most of that. Bounded deliberately: each probe is a full encode
     * of the whole animation, and conversion already takes minutes for a pack
     * of video stickers. The search keeps the largest result that fits and
     * never returns anything worse than what it was handed. */
    private suspend fun recoverDroppedFrames(
        sourceFrames: List<TimedFrame>,
        fittingCount: Int,
        smallestFailingCount: Int,
        fittingBytes: ByteArray,
        targetPx: Int,
        endTimestampMs: Int,
        minimizeSize: Boolean,
        ladder: IntArray,
        maxBytes: Int,
    ): ByteArray {
        // Nothing was ever cut, so there is nothing to win back.
        if (smallestFailingCount == Int.MAX_VALUE) return fittingBytes

        // Probed at the bottom of the ladder rather than at the quality that
        // just fit. A larger frame count only ever fits by spending less on
        // each frame, so re-probing at the same quality just re-derives the
        // count halving already found -- which is exactly what a first attempt
        // at this did, costing five times the encode time to change nothing.
        //
        // Trading sharpness for frames is the right way round here: this only
        // runs when the alternative is halving the animation, and a shake
        // rendered slightly softer still reads as a shake where a smooth drift
        // does not.
        val probeQuality = ladder.last()
        var best = fittingBytes
        var fits = fittingCount
        var fails = smallestFailingCount
        repeat(FRAME_RECOVERY_PROBES) {
            val candidateCount = (fits + fails) / 2
            if (candidateCount <= fits || candidateCount >= fails) return best
            coroutineContext.ensureActive()
            val candidate = FrameSamplingPolicy
                .evenlySpacedIndices(sourceFrames.size, candidateCount)
                .map(sourceFrames::get)
            val bytes = try {
                encodeOnce(
                    candidate,
                    targetPx,
                    endTimestampMs,
                    minimizeSize,
                    probeQuality,
                    ENCODE_METHOD,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return best
            }
            if (bytes.size in 1..maxBytes) {
                best = bytes
                fits = candidateCount
            } else {
                fails = candidateCount
            }
        }
        return best
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
}
