package com.royna.stickersftw.model

/** Which way the animated-WebP encoder should give ground when a sticker
 * won't fit WhatsApp's size cap: drop picture quality and keep the frames, or
 * drop frames and keep the picture sharp.
 *
 * Both ends still fill the same cap, so this barely moves the output size --
 * it decides what the budget is spent on. [Smoothness] also makes conversion
 * noticeably slower, since more encode attempts happen at the full frame
 * count before anything is given up. */
enum class ConversionBias {
    Sharpness,
    Auto,
    Smoothness,
}

/** A non-destructive crop in source-relative coordinates.
 *
 * Keeping this normalized rather than in preview pixels means the same crop
 * applies to a downsampled editor preview, a full-resolution photo, and every
 * decoded frame of a video. The editor produces a square in source pixels;
 * separate edges make the representation resilient to rounding and let the
 * conversion path validate data that came through storage or an Intent. */
data class MediaCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
