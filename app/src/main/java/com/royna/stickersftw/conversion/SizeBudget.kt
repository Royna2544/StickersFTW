package com.royna.stickersftw.conversion

/** Size/dimension/count constants pulled from WhatsApp's and Telegram's own
 * published sticker requirements -- see the plan doc / README for sources. */
object SizeBudget {
    const val STICKER_PX = 512
    const val TRAY_PX = 96

    const val STATIC_MAX_BYTES = 100_000
    const val ANIMATED_MAX_BYTES = 500_000
    const val TRAY_MAX_BYTES = 50_000

    const val TELEGRAM_STATIC_MAX_BYTES = 512_000
    const val TELEGRAM_VIDEO_MAX_BYTES = 256_000

    val QUALITY_STEPS = intArrayOf(80, 65, 50, 35, 20)

    const val MIN_FRAME_DURATION_MS = 8L
    const val MAX_TOTAL_DURATION_MS = 10_000L
    const val TELEGRAM_MAX_DURATION_MS = 3_000L

    /** Matches the rate Telegram video stickers are actually authored at, so
     * a typical source passes through whole rather than being decimated up
     * front.
     *
     * This was 20, which is not a clean divisor of a 30fps source: sampling
     * could only land on every second frame, so stickers played at 15fps and
     * the frames thrown away were gone before the size budget ever got a say.
     * Keeping them and letting the encoder decide is the better order --
     * quality is negotiable per sticker, frames that were never decoded are
     * not. If the budget can't hold them, WebpAnimationEncoder halves the
     * count, which is a clean 2:1 subdivision and lands back at 15fps evenly
     * spaced. Anything between those two rates would have to judder. */
    const val TARGET_FPS = 30

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30

    const val FALLBACK_EMOJI = "🙂" // 🙂 -- WhatsApp/Telegram both require >=1 emoji per sticker
    const val MAX_EMOJIS = 3
}
