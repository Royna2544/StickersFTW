package com.royna.stickersftw.network.dto

data class StickerDto(
    val id: String,
    val width: Int,
    val height: Int,
    val size: Int,
    val thumb: String? = null,
    val emoji: String? = null,
    /** Set only by [com.royna.stickersftw.network.DirectTelegramBackend], which
     * knows a sticker's real media type (is_video/is_animated) from the
     * getStickerSet response itself -- Telegram's file host doesn't reliably
     * set Content-Type, so this is used as a trustworthy stand-in for the
     * response-header sniffing [com.royna.stickersftw.conversion.StickerTypeClassifier]
     * otherwise relies on. Null (and ignored) for the server backend, which
     * has no such metadata but does get a real header from its own response. */
    val knownContentType: String? = null,
)

data class StickerSetDto(
    val name: String,
    val title: String,
    val stickers: List<StickerDto>,
)

data class BotInfoDto(
    val username: String,
)

data class UserVerifyDto(
    val started: Boolean,
)
