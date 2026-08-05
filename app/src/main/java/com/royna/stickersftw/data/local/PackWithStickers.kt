package com.royna.stickersftw.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class PackWithStickers(
    @Embedded val pack: PackEntity,
    @Relation(parentColumn = "id", entityColumn = "packId")
    val stickers: List<StickerEntity>,
)
