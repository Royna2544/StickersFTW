package com.royna.stickersftw.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules WhatsApp enforces and will not explain. Its own answer to any
 * failure here is a single toast -- "There's a problem with the sticker
 * pack" -- so each case below is a pack that converts, lists, looks right,
 * and simply cannot be added.
 */
class WhatsappPackValidatorTest {

    private fun info(
        width: Int = SizeBudget.STICKER_PX,
        height: Int = SizeBudget.STICKER_PX,
        durations: List<Int> = emptyList(),
        bytes: Long = 40_000,
    ) = WebpInfo(
        width = width,
        height = height,
        isAnimated = durations.isNotEmpty(),
        hasAlpha = true,
        frameDurationsMs = durations,
        byteCount = bytes,
    )

    private fun tray(width: Int = SizeBudget.TRAY_PX, height: Int = SizeBudget.TRAY_PX, bytes: Long = 3_000) =
        info(width, height, bytes = bytes)

    private fun sticker(
        label: String = "a.webp",
        info: WebpInfo? = info(),
        emojiCount: Int = 1,
    ) = StickerToCheck(label, info, emojiCount)

    private fun pack(
        isAnimated: Boolean = false,
        stickers: List<StickerToCheck> = List(3) { sticker(label = "$it.webp") },
        tray: WebpInfo? = tray(),
        identifier: String = "pack-1",
        name: String = "Hot Cherry",
        publisher: String = "@HotCherry",
    ) = PackToCheck(identifier, name, publisher, isAnimated, tray, stickers)

    private fun animatedSticker(label: String = "a.webp", frames: Int = 10, durationMs: Int = 40) =
        sticker(label, info(durations = List(frames) { durationMs }, bytes = 300_000))

    @Test
    fun anOrdinaryStaticPackPasses() {
        assertEquals(emptyList<PackViolation>(), WhatsappPackValidator.validate(pack()))
    }

    @Test
    fun anOrdinaryAnimatedPackPasses() {
        val animated = pack(isAnimated = true, stickers = List(3) { animatedSticker("$it.webp") })

        assertEquals(emptyList<PackViolation>(), WhatsappPackValidator.validate(animated))
    }

    /** The bug this session began with: a pack whose tray never got built.
     * Everything else about it is fine. */
    @Test
    fun aPackWithNoTrayIconIsRejected() {
        val violations = WhatsappPackValidator.validate(pack(tray = null))

        assertEquals(listOf(PackViolation.TrayMissing), violations)
    }

    @Test
    fun aTrayOutsideTheAllowedSizeRangeIsRejected() {
        assertTrue(
            WhatsappPackValidator.validate(pack(tray = tray(width = 16, height = 16)))
                .contains(PackViolation.TrayWrongSize(16, 16)),
        )
        assertTrue(
            WhatsappPackValidator.validate(pack(tray = tray(width = 600, height = 600)))
                .contains(PackViolation.TrayWrongSize(600, 600)),
        )
    }

    /** 96x96 is what this app happens to produce, but the contract is a
     * range -- so a tray at either end must pass, or the validator would
     * reject packs WhatsApp accepts. */
    @Test
    fun aTrayAnywhereInTheAllowedRangePasses() {
        listOf(24, 96, 512).forEach { size ->
            assertEquals(
                "a ${size}px tray was rejected",
                emptyList<PackViolation>(),
                WhatsappPackValidator.validate(pack(tray = tray(size, size))),
            )
        }
    }

    @Test
    fun aTrayOverFiftyKilobytesIsRejected() {
        val violations = WhatsappPackValidator.validate(pack(tray = tray(bytes = 60_000)))

        assertTrue(violations.contains(PackViolation.TrayTooLarge(60_000, SizeBudget.TRAY_MAX_BYTES)))
    }

    @Test
    fun packsOutsideTheStickerCountRangeAreRejected() {
        val tooFew = WhatsappPackValidator.validate(pack(stickers = List(2) { sticker("$it.webp") }))
        val tooMany = WhatsappPackValidator.validate(pack(stickers = List(31) { sticker("$it.webp") }))

        assertTrue(tooFew.contains(PackViolation.StickerCount(2)))
        assertTrue(tooMany.contains(PackViolation.StickerCount(31)))
    }

    @Test
    fun theCountBoundariesThemselvesPass() {
        assertTrue(WhatsappPackValidator.isValid(pack(stickers = List(3) { sticker("$it.webp") })))
        assertTrue(WhatsappPackValidator.isValid(pack(stickers = List(30) { sticker("$it.webp") })))
    }

    /** All-or-nothing per pack: one still sticker rejects an entire animated
     * pack, which is why the converter re-encodes outliers rather than
     * shipping them. */
    @Test
    fun oneStillStickerRejectsAnAnimatedPack() {
        val stickers = List(2) { animatedSticker("$it.webp") } + sticker("still.webp")

        val violations = WhatsappPackValidator.validate(pack(isAnimated = true, stickers = stickers))

        assertTrue(violations.contains(PackViolation.WrongStickerType("still.webp", 1, true)))
    }

    @Test
    fun oneAnimatedStickerRejectsAStaticPack() {
        val stickers = List(2) { sticker("$it.webp") } + animatedSticker("moving.webp")

        val violations = WhatsappPackValidator.validate(pack(isAnimated = false, stickers = stickers))

        assertTrue(violations.contains(PackViolation.WrongStickerType("moving.webp", 10, false)))
    }

    /** A frame under the floor is encoded, paid for in bytes, and never
     * displayed. A real 30-sticker pack once shipped 189 of them. */
    @Test
    fun framesBelowTheMinimumDurationAreReported() {
        val bad = sticker("dead.webp", info(durations = listOf(40, 0, 40, 3), bytes = 300_000))

        val violations = WhatsappPackValidator.validate(pack(isAnimated = true, stickers = listOf(bad, animatedSticker("b.webp"), animatedSticker("c.webp"))))

        assertTrue(violations.contains(PackViolation.FrameTooShort("dead.webp", 0)))
        assertTrue(violations.contains(PackViolation.FrameTooShort("dead.webp", 3)))
    }

    @Test
    fun aFrameExactlyAtTheMinimumIsAccepted() {
        val edge = sticker("edge.webp", info(durations = listOf(8, 8, 8), bytes = 300_000))

        val violations = WhatsappPackValidator.validate(
            pack(isAnimated = true, stickers = listOf(edge, animatedSticker("b.webp"), animatedSticker("c.webp"))),
        )

        assertEquals(emptyList<PackViolation>(), violations)
    }

    @Test
    fun anAnimationOverTenSecondsIsRejected() {
        val long = sticker("long.webp", info(durations = List(101) { 100 }, bytes = 300_000))

        val violations = WhatsappPackValidator.validate(
            pack(isAnimated = true, stickers = listOf(long, animatedSticker("b.webp"), animatedSticker("c.webp"))),
        )

        assertTrue(violations.contains(PackViolation.AnimationTooLong("long.webp", 10_100)))
    }

    @Test
    fun stickersAreHeldToTheirPacksSizeBudget() {
        val fatStatic = sticker("fat.webp", info(bytes = 120_000))
        val fatAnimated = sticker("fat.webp", info(durations = List(10) { 40 }, bytes = 600_000))

        assertTrue(
            WhatsappPackValidator.validate(pack(stickers = listOf(fatStatic, sticker("b.webp"), sticker("c.webp"))))
                .contains(PackViolation.StickerTooLarge("fat.webp", 120_000, SizeBudget.STATIC_MAX_BYTES)),
        )
        assertTrue(
            WhatsappPackValidator.validate(
                pack(isAnimated = true, stickers = listOf(fatAnimated, animatedSticker("b.webp"), animatedSticker("c.webp"))),
            ).contains(PackViolation.StickerTooLarge("fat.webp", 600_000, SizeBudget.ANIMATED_MAX_BYTES)),
        )
    }

    /** An animated sticker is allowed five times what a still one is, so
     * holding it to the static budget would reject perfectly good packs. */
    @Test
    fun anAnimatedStickerMayExceedTheStaticBudget() {
        val big = sticker("big.webp", info(durations = List(10) { 40 }, bytes = 480_000))

        val violations = WhatsappPackValidator.validate(
            pack(isAnimated = true, stickers = listOf(big, animatedSticker("b.webp"), animatedSticker("c.webp"))),
        )

        assertEquals(emptyList<PackViolation>(), violations)
    }

    @Test
    fun stickersThatAreNotFiveTwelveSquareAreRejected() {
        val odd = sticker("odd.webp", info(width = 512, height = 384))

        val violations = WhatsappPackValidator.validate(pack(stickers = listOf(odd, sticker("b.webp"), sticker("c.webp"))))

        assertTrue(violations.contains(PackViolation.StickerNotSquare("odd.webp", 512, 384)))
    }

    /** WhatsApp requires at least one emoji per sticker, and Telegram does
     * not guarantee one -- which is why the converter carries a fallback. */
    @Test
    fun aStickerWithNoEmojiIsRejected() {
        val bare = sticker("bare.webp", emojiCount = 0)

        val violations = WhatsappPackValidator.validate(pack(stickers = listOf(bare, sticker("b.webp"), sticker("c.webp"))))

        assertTrue(violations.contains(PackViolation.EmojiCount("bare.webp", 0)))
    }

    @Test
    fun aStickerWithTooManyEmojiIsRejected() {
        val many = sticker("many.webp", emojiCount = 4)

        val violations = WhatsappPackValidator.validate(pack(stickers = listOf(many, sticker("b.webp"), sticker("c.webp"))))

        assertTrue(violations.contains(PackViolation.EmojiCount("many.webp", 4)))
    }

    /** A file the app wrote a path for but nobody can open is exactly the
     * case that produces an unaddable pack, so it is a violation rather than
     * something to skip over. */
    @Test
    fun anUnreadableStickerIsAViolationRatherThanIgnored() {
        val broken = sticker("broken.webp", info = null)

        val violations = WhatsappPackValidator.validate(pack(stickers = listOf(broken, sticker("b.webp"), sticker("c.webp"))))

        assertTrue(violations.contains(PackViolation.UnreadableSticker("broken.webp")))
    }

    @Test
    fun blankAndOverlongFieldsAreRejected() {
        assertTrue(
            WhatsappPackValidator.validate(pack(name = "  "))
                .contains(PackViolation.FieldBlank(PackField.NAME)),
        )
        val long = "x".repeat(129)
        assertTrue(
            WhatsappPackValidator.validate(pack(publisher = long))
                .contains(PackViolation.FieldTooLong(PackField.PUBLISHER, 129, 128)),
        )
    }

    @Test
    fun anIdentifierWithIllegalCharactersIsRejected() {
        val violations = WhatsappPackValidator.validate(pack(identifier = "pack/../etc"))

        assertTrue(violations.any { it is PackViolation.IdentifierInvalid })
    }

    /** Two periods in a row are refused on their own, because an identifier
     * becomes a path segment and ".." in one reaches outside the pack. */
    @Test
    fun anIdentifierWithConsecutivePeriodsIsRejected() {
        val violations = WhatsappPackValidator.validate(pack(identifier = "pack..one"))

        assertTrue(violations.any { it is PackViolation.IdentifierInvalid })
    }

    @Test
    fun ordinaryIdentifiersPass() {
        listOf("pack-1", "a_b.c", "O'Brien's pack", "5f3a9c2e").forEach {
            assertEquals(
                "identifier $it was rejected",
                emptyList<PackViolation>(),
                WhatsappPackValidator.validate(pack(identifier = it)),
            )
        }
    }

    /** A UUID is what this app actually uses as an identifier, so it had
     * better pass. */
    @Test
    fun theIdentifierShapeThisAppProducesPasses() {
        val uuid = "abf46598-35f7-49ca-a9d9-01bf19cf06cc"

        assertTrue(WhatsappPackValidator.isValid(pack(identifier = uuid)))
    }

    /** Every problem at once, so a user fixing one is not sent back for the
     * next: the validator reports them all rather than stopping at the first. */
    @Test
    fun everyProblemIsReportedNotJustTheFirst() {
        val violations = WhatsappPackValidator.validate(
            pack(
                tray = null,
                stickers = listOf(sticker("a.webp", info(width = 100, height = 100), emojiCount = 0)),
            ),
        )

        assertTrue(violations.contains(PackViolation.TrayMissing))
        assertTrue(violations.contains(PackViolation.StickerCount(1)))
        assertTrue(violations.contains(PackViolation.StickerNotSquare("a.webp", 100, 100)))
        assertTrue(violations.contains(PackViolation.EmojiCount("a.webp", 0)))
    }
}
