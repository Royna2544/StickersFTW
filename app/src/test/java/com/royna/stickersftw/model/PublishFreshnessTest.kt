package com.royna.stickersftw.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishFreshnessTest {
    @Test
    fun whatsappRequiresPresenceAndMatchingAcknowledgedRevision() {
        assertEquals(
            WhatsappFreshnessState.NotAdded,
            deriveWhatsappFreshness(whatsappAdded = false, imageDataVersion = 4, syncedDataVersion = 4),
        )
        assertEquals(
            WhatsappFreshnessState.Current,
            deriveWhatsappFreshness(whatsappAdded = true, imageDataVersion = 4, syncedDataVersion = 4),
        )
        assertEquals(
            WhatsappFreshnessState.NeedsRefresh,
            deriveWhatsappFreshness(whatsappAdded = true, imageDataVersion = 5, syncedDataVersion = 4),
        )
        assertEquals(
            WhatsappFreshnessState.NeedsRefresh,
            deriveWhatsappFreshness(whatsappAdded = true, imageDataVersion = 1, syncedDataVersion = null),
        )
    }

    @Test
    fun importedTelegramSourceIsNotAUserPush() {
        assertEquals(
            TelegramFreshnessState.NotPushed,
            deriveTelegramFreshness(
                origin = PackOrigin.Imported,
                imageDataVersion = 3,
                syncedDataVersion = 3,
                hasTelegramSet = true,
                pushedStickerCount = 12,
                totalStickerCount = 12,
            ),
        )
    }

    @Test
    fun telegramInitialAndRetryStatesDependOnCompletion() {
        assertEquals(
            TelegramFreshnessState.NotPushed,
            telegramFreshness(synced = null, pushed = 0, total = 4, hasSet = false),
        )
        assertEquals(
            TelegramFreshnessState.NotPushed,
            telegramFreshness(synced = null, pushed = 2, total = 4, hasSet = false),
        )
        assertEquals(
            TelegramFreshnessState.Partial,
            telegramFreshness(synced = null, pushed = 2, total = 4),
        )
        assertEquals(
            TelegramFreshnessState.Partial,
            telegramFreshness(synced = 7, pushed = 2, total = 4),
        )
        assertEquals(
            TelegramFreshnessState.Current,
            telegramFreshness(synced = 7, pushed = 4, total = 4),
        )
        // A full-looking legacy row without an acknowledged revision is not
        // allowed to claim Current.
        assertEquals(
            TelegramFreshnessState.Partial,
            telegramFreshness(synced = null, pushed = 4, total = 4),
        )
    }

    @Test
    fun staleTelegramRevisionWinsOverPartialCounts() {
        assertEquals(
            TelegramFreshnessState.OutOfDate,
            telegramFreshness(imageVersion = 8, synced = 7, pushed = 4, total = 4),
        )
        assertEquals(
            TelegramFreshnessState.OutOfDate,
            telegramFreshness(imageVersion = 8, synced = 7, pushed = 2, total = 4),
        )
    }

    private fun telegramFreshness(
        imageVersion: Int = 7,
        synced: Int?,
        pushed: Int,
        total: Int,
        hasSet: Boolean = true,
    ) = deriveTelegramFreshness(
        origin = PackOrigin.Created,
        imageDataVersion = imageVersion,
        syncedDataVersion = synced,
        hasTelegramSet = hasSet,
        pushedStickerCount = pushed,
        totalStickerCount = total,
    )
}
