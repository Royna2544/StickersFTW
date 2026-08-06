package com.royna.stickersftw.data

import com.royna.stickersftw.network.dto.StickerSetDto

/** Computes a cheap, stable fingerprint of a Telegram sticker set's *full*
 * content (title + every sticker's id:emoji, in order) so a later re-fetch
 * can detect drift -- any added/removed/reordered sticker, emoji change, or
 * title change changes the signature. */
object SourceSignature {
    fun compute(dto: StickerSetDto): String =
        (listOf(dto.title) + dto.stickers.map { "${it.id}:${it.emoji.orEmpty()}" }).joinToString("|")
}
