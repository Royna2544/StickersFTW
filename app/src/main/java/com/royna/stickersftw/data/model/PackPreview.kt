package com.royna.stickersftw.data.model

data class PackPreview(
    val shortName: String,
    val title: String,
    val totalStickerCount: Int,
    /** Number of <=30-sticker parts this pack will be split into. 1 when
     * the whole pack already fits in a single WhatsApp/Telegram pack. */
    val partCount: Int,
    val emojis: List<String>,
    val warning: String? = null,
)

sealed class PreviewResult {
    data class Loaded(val preview: PackPreview) : PreviewResult()
    data class Error(val message: String) : PreviewResult()
}
