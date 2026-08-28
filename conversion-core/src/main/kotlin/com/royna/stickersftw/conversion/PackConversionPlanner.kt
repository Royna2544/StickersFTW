package com.royna.stickersftw.conversion

/** Pure Kotlin, with no Android framework dependency. */
sealed class PlannerResult<T> {
    data class Ok<T>(val items: List<T>, val warning: String? = null) : PlannerResult<T>()
    data class Rejected<T>(val reason: String) : PlannerResult<T>()
}

object PackConversionPlanner {
    /** Majority-vote the pack's overall animated/static classification from
     * its stickers' individually sniffed types. Ties break toward animated
     * (arbitrary but documented) since only a boolean is ultimately needed. */
    fun classifyPackIsAnimated(types: List<StickerMediaType>): Boolean {
        val animatedCount = types.count {
            it == StickerMediaType.AnimatedLottie || it == StickerMediaType.Video
        }
        val staticCount = types.count { it == StickerMediaType.Static }
        return animatedCount >= staticCount
    }

    /** Rejects packs below WhatsApp/Telegram's shared 3-sticker minimum. */
    fun <T> applyCountRules(items: List<T>): PlannerResult<T> {
        if (items.size < SizeBudget.MIN_STICKERS) {
            return PlannerResult.Rejected(
                "This pack has only ${items.size} sticker(s); at least " +
                    "${SizeBudget.MIN_STICKERS} are required.",
            )
        }
        return PlannerResult.Ok(items)
    }

    /** Evenly splits a sticker set into <=30-sticker parts (WhatsApp's and
     * Telegram's shared cap) rather than truncating -- e.g. 91 stickers
     * become 4 parts of ~23 each instead of 30 + 30 + 30 + 1. Each range is
     * guaranteed to be no larger than MAX_STICKERS. */
    fun computePartRanges(totalStickers: Int): List<IntRange> {
        if (totalStickers <= 0) return emptyList()

        val partCount = ((totalStickers + SizeBudget.MAX_STICKERS - 1) / SizeBudget.MAX_STICKERS)
            .coerceAtLeast(1)
        val baseSize = totalStickers / partCount
        val remainder = totalStickers % partCount

        val ranges = mutableListOf<IntRange>()
        var start = 0
        repeat(partCount) { index ->
            val size = baseSize + if (index < remainder) 1 else 0
            ranges.add(start until (start + size))
            start += size
        }
        return ranges
    }

    /** Normalizes a free-text/comma-or-space-separated emoji list to 1-3
     * entries, falling back to a default when none were given -- both
     * WhatsApp and Telegram require at least one emoji per sticker. */
    fun normalizeEmojis(raw: String?): List<String> {
        val cleaned = raw
            ?.split(Regex("[,\\s]+"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return if (cleaned.isEmpty()) listOf(SizeBudget.FALLBACK_EMOJI) else cleaned.take(SizeBudget.MAX_EMOJIS)
    }
}
