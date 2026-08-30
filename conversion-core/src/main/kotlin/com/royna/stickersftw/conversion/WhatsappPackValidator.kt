package com.royna.stickersftw.conversion

/** Everything WhatsApp checks before it will accept a third-party pack,
 * mirrored from its published StickerPackValidator.
 *
 * WhatsApp's own answer to any of these is one toast -- "There's a problem
 * with the sticker pack" -- with no indication of which rule failed or which
 * sticker failed it. A pack that took minutes to convert simply cannot be
 * added, and nothing says why. Checking here first is what lets the app name
 * the actual problem, and what lets a batch run over many packs produce a
 * verdict instead of a pile of files somebody has to inspect by hand.
 *
 * Every limit comes from [SizeBudget], so the rules the converter targets and
 * the rules it is checked against cannot drift apart. */
sealed interface PackViolation {

    /** Fewer than the floor, or more than the cap. */
    data class StickerCount(val count: Int) : PackViolation

    data class StickerTooLarge(val sticker: String, val bytes: Long, val limitBytes: Int) : PackViolation

    data class StickerNotSquare(val sticker: String, val width: Int, val height: Int) : PackViolation

    /** WhatsApp is all-or-nothing per pack: if the pack says animated, every
     * sticker must have more than one frame, and if it says static, every
     * sticker must have exactly one. A single outlier rejects the whole pack,
     * not just that sticker. */
    data class WrongStickerType(
        val sticker: String,
        val frameCount: Int,
        val packIsAnimated: Boolean,
    ) : PackViolation

    /** A frame shorter than the floor -- including a zero-duration frame,
     * which is encoded and paid for in bytes but never displayed. */
    data class FrameTooShort(val sticker: String, val durationMs: Int) : PackViolation

    data class AnimationTooLong(val sticker: String, val totalMs: Int) : PackViolation

    data class EmojiCount(val sticker: String, val count: Int) : PackViolation

    data class UnreadableSticker(val sticker: String) : PackViolation

    /** No tray icon at all. A pack without one is refused outright. */
    data object TrayMissing : PackViolation

    data class TrayTooLarge(val bytes: Long, val limitBytes: Int) : PackViolation

    data class TrayWrongSize(val width: Int, val height: Int) : PackViolation

    data class FieldBlank(val field: String) : PackViolation

    data class FieldTooLong(val field: String, val length: Int, val limit: Int) : PackViolation

    data class IdentifierInvalid(val identifier: String, val reason: String) : PackViolation
}

/** One sticker as the validator sees it. [info] is null when the file could
 * not be read at all, which is itself a violation rather than something to
 * skip -- a manifest naming a file nobody can open is exactly the case that
 * produced an unaddable pack. */
data class StickerToCheck(
    val label: String,
    val info: WebpInfo?,
    val emojiCount: Int,
)

data class PackToCheck(
    val identifier: String,
    val name: String,
    val publisher: String,
    val isAnimated: Boolean,
    val tray: WebpInfo?,
    val stickers: List<StickerToCheck>,
)

object WhatsappPackValidator {
    /** WhatsApp's limit on identifier, name and publisher alike. */
    const val MAX_FIELD_LENGTH = 128

    /** A tray icon may be any square-ish size in this range; it does not have
     * to be exactly [SizeBudget.TRAY_PX], which is merely what this app
     * produces. */
    const val MIN_TRAY_PX = 24
    const val MAX_TRAY_PX = 512

    private val IDENTIFIER_ALLOWED = Regex("[\\w-.' ]+")

    fun validate(pack: PackToCheck): List<PackViolation> {
        val violations = mutableListOf<PackViolation>()

        violations += checkFields(pack)
        violations += checkTray(pack.tray)

        if (pack.stickers.size !in SizeBudget.MIN_STICKERS..SizeBudget.MAX_STICKERS) {
            violations += PackViolation.StickerCount(pack.stickers.size)
        }
        pack.stickers.forEach { violations += checkSticker(it, pack.isAnimated) }

        return violations
    }

    fun isValid(pack: PackToCheck): Boolean = validate(pack).isEmpty()

    private fun checkFields(pack: PackToCheck): List<PackViolation> {
        val violations = mutableListOf<PackViolation>()
        listOf(
            "identifier" to pack.identifier,
            "name" to pack.name,
            "publisher" to pack.publisher,
        ).forEach { (field, value) ->
            if (value.isBlank()) {
                violations += PackViolation.FieldBlank(field)
            } else if (value.length > MAX_FIELD_LENGTH) {
                violations += PackViolation.FieldTooLong(field, value.length, MAX_FIELD_LENGTH)
            }
        }

        if (pack.identifier.isNotBlank()) {
            if (!IDENTIFIER_ALLOWED.matches(pack.identifier)) {
                violations += PackViolation.IdentifierInvalid(
                    pack.identifier,
                    "may only contain letters, digits, underscores, hyphens, periods, " +
                        "apostrophes and spaces",
                )
            } else if (pack.identifier.contains("..")) {
                // Rejected on its own: an identifier becomes a path segment,
                // and ".." in one is how a pack would reach outside its
                // own directory.
                violations += PackViolation.IdentifierInvalid(
                    pack.identifier,
                    "may not contain two periods in a row",
                )
            }
        }

        return violations
    }

    private fun checkTray(tray: WebpInfo?): List<PackViolation> {
        if (tray == null) return listOf(PackViolation.TrayMissing)
        val violations = mutableListOf<PackViolation>()
        if (tray.byteCount > SizeBudget.TRAY_MAX_BYTES) {
            violations += PackViolation.TrayTooLarge(tray.byteCount, SizeBudget.TRAY_MAX_BYTES)
        }
        if (tray.width !in MIN_TRAY_PX..MAX_TRAY_PX || tray.height !in MIN_TRAY_PX..MAX_TRAY_PX) {
            violations += PackViolation.TrayWrongSize(tray.width, tray.height)
        }
        return violations
    }

    private fun checkSticker(sticker: StickerToCheck, packIsAnimated: Boolean): List<PackViolation> {
        val info = sticker.info ?: return listOf(PackViolation.UnreadableSticker(sticker.label))
        val violations = mutableListOf<PackViolation>()

        if (sticker.emojiCount !in 1..SizeBudget.MAX_EMOJIS) {
            violations += PackViolation.EmojiCount(sticker.label, sticker.emojiCount)
        }

        if (info.width != SizeBudget.STICKER_PX || info.height != SizeBudget.STICKER_PX) {
            violations += PackViolation.StickerNotSquare(sticker.label, info.width, info.height)
        }

        // The budget a sticker is held to is the pack's, not its own: a still
        // image inside an animated pack is already rejected for its frame
        // count, and reporting it as oversized too would be noise.
        val limit = if (packIsAnimated) SizeBudget.ANIMATED_MAX_BYTES else SizeBudget.STATIC_MAX_BYTES
        if (info.byteCount > limit) {
            violations += PackViolation.StickerTooLarge(sticker.label, info.byteCount, limit)
        }

        val animated = info.frameCount > 1
        if (animated != packIsAnimated) {
            violations += PackViolation.WrongStickerType(sticker.label, info.frameCount, packIsAnimated)
        }

        if (animated) {
            info.frameDurationsMs
                .filter { it < SizeBudget.MIN_FRAME_DURATION_MS }
                .forEach { violations += PackViolation.FrameTooShort(sticker.label, it) }
            if (info.totalDurationMs > SizeBudget.MAX_TOTAL_DURATION_MS) {
                violations += PackViolation.AnimationTooLong(sticker.label, info.totalDurationMs)
            }
        }

        return violations
    }
}
