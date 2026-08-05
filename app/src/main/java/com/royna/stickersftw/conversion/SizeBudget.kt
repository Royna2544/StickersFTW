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
    const val TARGET_FPS = 20

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30

    const val FALLBACK_EMOJI = "🙂" // 🙂 -- WhatsApp/Telegram both require >=1 emoji per sticker
    const val MAX_EMOJIS = 3
}
