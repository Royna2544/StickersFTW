package com.royna.stickersftw.conversion

import java.io.File

/** The bot server gives no format field in its JSON -- the sniffed
 * Content-Type header on the binary download is the only signal for
 * whether a fetched sticker is static/animated/video. */
object StickerTypeClassifier {
    fun classify(contentType: String?): StickerMediaType =
        when (contentType?.substringBefore(';')?.trim()) {
            "image/webp", "image/jpeg" -> StickerMediaType.Static
            "video/webm" -> StickerMediaType.Video
            "application/x-tgsticker" -> StickerMediaType.AnimatedLottie
            else -> StickerMediaType.Unknown
        }

    /** Best-effort reclassification for octet-stream/unknown Content-Type:
     * peek the gzip magic bytes (a .tgs file is gzip-compressed JSON). */
    fun reclassifyUnknown(file: File): StickerMediaType = try {
        file.inputStream().use { input ->
            val header = ByteArray(2)
            val read = input.read(header)
            if (read == 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                StickerMediaType.AnimatedLottie
            } else {
                StickerMediaType.Static
            }
        }
    } catch (_: Exception) {
        StickerMediaType.Static
    }
}
