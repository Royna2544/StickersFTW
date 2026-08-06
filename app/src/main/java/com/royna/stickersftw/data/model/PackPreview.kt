package com.royna.stickersftw.data.model

data class PreviewSticker(
    val id: String,
    val emoji: String?,
)

data class PackPreview(
    val shortName: String,
    val title: String,
    val totalStickerCount: Int,
    /** Number of <=30-sticker parts this pack will be split into. 1 when
     * the whole pack already fits in a single WhatsApp/Telegram pack. */
    val partCount: Int,
    /** Every sticker in the source pack, in order -- used by the custom
     * picker, which lets the user hand-select an arbitrary <=30 subset
     * instead of a contiguous part. */
    val stickers: List<PreviewSticker>,
    val emojis: List<String>,
    val warning: String? = null,
)

sealed class PreviewResult {
    data class Loaded(val preview: PackPreview) : PreviewResult()
    data class Error(val message: String) : PreviewResult()
}
