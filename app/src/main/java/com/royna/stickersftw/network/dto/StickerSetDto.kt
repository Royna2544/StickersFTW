package com.royna.stickersftw.network.dto

data class StickerDto(
    val id: String,
    val width: Int,
    val height: Int,
    val size: Int,
    val thumb: String? = null,
    val emoji: String? = null,
)

data class StickerSetDto(
    val name: String,
    val title: String,
    val stickers: List<StickerDto>,
)

data class BotInfoDto(
    val username: String,
)
