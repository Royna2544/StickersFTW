package com.royna.stickersftw.data

import com.royna.stickersftw.network.dto.StickerSetDto

/** Computes a cheap, stable fingerprint of a Telegram sticker set's *full*
 * content (title + every sticker's id:emoji, in order) so a later re-fetch
 * can detect drift -- any added/removed/reordered sticker, emoji change, or
 * title change changes the signature. */
object SourceSignature {
    fun compute(dto: StickerSetDto): String =
        (listOf(dto.title) + dto.stickers.map { "${it.id}:${it.emoji.orEmpty()}" }).joinToString("|")

    /** Reads a signature back apart, so a stored one can be compared against
     * a fresh fetch field by field rather than just "same or not". Only
     * possible because the format kept the real ids and emoji instead of
     * hashing them -- a hash would be smaller and useless for this. */
    fun parse(signature: String?): Parsed? {
        if (signature.isNullOrBlank()) return null
        val parts = signature.split("|")
        val stickers = parts.drop(1).mapNotNull { entry ->
            val separator = entry.indexOf(':')
            if (separator < 0) null else Entry(entry.take(separator), entry.substring(separator + 1))
        }
        return Parsed(parts.first(), stickers)
    }

    data class Entry(val id: String, val emoji: String)

    data class Parsed(val title: String, val stickers: List<Entry>)
}
