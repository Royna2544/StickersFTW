package com.royna.stickersftw.data.model

/** What actually changed in a pack's Telegram source since it was imported.
 *
 * The update flag only ever said "something is different", which is a poor
 * basis for asking someone to spend minutes re-converting. This is the
 * difference itself, built by comparing the signature stored at import
 * against a fresh fetch. */
data class PackUpdateDiff(
    val titleBefore: String,
    val titleAfter: String,
    val added: List<StickerEntry>,
    val removed: List<StickerEntry>,
    val emojiChanged: List<EmojiChange>,
    val countBefore: Int,
    val countAfter: Int,
) {
    val titleChanged: Boolean get() = titleBefore != titleAfter

    /** A signature can differ without any of the fields above differing --
     * the stickers were reordered. Worth saying so explicitly rather than
     * showing an empty diff and looking broken. */
    val isReorderOnly: Boolean
        get() = !titleChanged && added.isEmpty() && removed.isEmpty() && emojiChanged.isEmpty()
}

data class StickerEntry(val id: String, val emoji: String)

data class EmojiChange(val id: String, val before: String, val after: String)

sealed class PackUpdateDiffResult {
    data class Loaded(val diff: PackUpdateDiff) : PackUpdateDiffResult()

    /** The pack predates signature storage, so there is nothing to compare
     * against. Updating still works; it just can't be previewed. */
    data object NoBaseline : PackUpdateDiffResult()
    data object UpToDate : PackUpdateDiffResult()
    data class Error(val message: String) : PackUpdateDiffResult()
}
