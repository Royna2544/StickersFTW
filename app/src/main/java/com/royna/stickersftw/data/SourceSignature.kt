package com.royna.stickersftw.data

import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto

/** Computes a cheap, stable fingerprint of a Telegram sticker set's *full*
 * content (title + every sticker's stable identity and emoji, in order) so a
 * later re-fetch can detect drift. Telegram may rotate the downloadable
 * `file_id` for unchanged content; `file_unique_id` remains stable and is
 * preferred when the backend exposes it. */
object SourceSignature {
    fun compute(dto: StickerSetDto): String =
        (listOf(dto.title) + dto.stickers.map { "${identityOf(it)}:${it.emoji.orEmpty()}" }).joinToString("|")

    /** Compatibility check for signatures written before direct Telegram
     * exposed `file_unique_id`. If the old download locators still match, the
     * caller can safely replace that legacy baseline with [compute]'s stable
     * representation without showing a false update prompt. */
    fun matches(dto: StickerSetDto, stored: String?): Boolean {
        if (stored == null) return false
        return compute(dto) == stored || computeWithDownloadLocators(dto) == stored
    }

    /** Returns the identity used by both signatures and field-level update
     * comparisons. [StickerDto.id] remains the download locator. */
    fun identityOf(sticker: StickerDto): String = sticker.stableId ?: sticker.id

    /** Matches a freshly fetched sticker against identities retained on a
     * local row. Checking both the normalized identity and current locator
     * covers direct/server backend switches as well as historical rows that
     * predate `file_unique_id` persistence. */
    fun matchesAnyStoredIdentity(sticker: StickerDto, storedIdentities: Set<String>): Boolean =
        identityOf(sticker) in storedIdentities || sticker.id in storedIdentities

    private fun computeWithDownloadLocators(dto: StickerSetDto): String =
        (listOf(dto.title) + dto.stickers.map { "${it.id}:${it.emoji.orEmpty()}" }).joinToString("|")

    /** Reads a signature back apart, so a stored one can be compared against
     * a fresh fetch field by field rather than just "same or not". Only
     * possible because the format kept each chosen identity and emoji instead
     * of hashing them -- a hash would be smaller and useless for this. */
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
