package com.royna.stickersftw.model

import java.text.BreakIterator
import java.util.Locale

/** Parses one to three emoji grapheme clusters, accepting either adjacent
 * emoji or comma/whitespace-separated input. Plain text and mixed input are
 * rejected so WhatsApp/Telegram metadata cannot silently become arbitrary
 * labels. */
fun parseStickerEmojis(value: String): List<String>? {
    val compact = value.filterNot { it == ',' || it.isWhitespace() }
    if (compact.isEmpty()) return null

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(compact) }
    val graphemes = buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(compact.substring(start, end))
            start = end
            end = iterator.next()
        }
    }
    if (graphemes.any { !it.looksLikeEmoji() }) return null
    return graphemes.distinct().takeIf { it.size in 1..3 }
}

private fun String.looksLikeEmoji(): Boolean {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (codePoint.isEmojiBase()) return true
        index += Character.charCount(codePoint)
    }
    return false
}

private fun Int.isEmojiBase(): Boolean =
    this in 0x1F000..0x1FAFF ||
        this in 0x2600..0x27BF ||
        this in 0x1F1E6..0x1F1FF ||
        this in listOf(
            0x00A9,
            0x00AE,
            0x203C,
            0x2049,
            0x2122,
            0x2139,
            0x3030,
            0x303D,
            0x3297,
            0x3299,
        ) ||
        this in 0x30..0x39 ||
        this == 0x23 ||
        this == 0x2A
