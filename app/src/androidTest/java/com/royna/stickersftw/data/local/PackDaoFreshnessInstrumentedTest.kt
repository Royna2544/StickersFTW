package com.royna.stickersftw.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.TelegramFreshnessState
import com.royna.stickersftw.model.deriveTelegramFreshness
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackDaoFreshnessInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = AppDatabase.getInstance(context)
    private val repository = StickerPackRepository(context)
    private val packId = "freshness-test-${UUID.randomUUID()}"

    @Before
    fun seedPack() {
        runBlocking {
            val now = System.currentTimeMillis()
            database.packDao().upsert(
                PackEntity(
                id = packId,
                origin = PackOrigin.Created.name,
                telegramSetName = "freshness_test_by_bot",
                pushShortName = "freshness_test",
                sourceUrl = null,
                title = "Freshness test",
                publisher = "Tester",
                stickerCount = 3,
                isAnimatedPack = false,
                status = PackStatus.Ready.name,
                errorMessage = null,
                warningMessage = null,
                trayIconPath = null,
                isPinned = false,
                whatsappAdded = false,
                createdAtMillis = now,
                updatedAtMillis = now,
                imageDataVersion = 4,
                ),
            )
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            database.packDao().delete(packId)
        }
    }

    @Test
    fun passiveWhatsappPresenceDoesNotAcknowledgeRevision() = runBlocking {
        database.packDao().setWhatsappAdded(packId, true)

        val passivelyDetected = database.packDao().getPack(packId)!!
        assertTrue(passivelyDetected.whatsappAdded)
        assertNull(passivelyDetected.whatsappSyncedDataVersion)

        assertEquals(1, database.packDao().acknowledgeWhatsappInstall(packId, expectedRevision = 4))
        val acknowledged = database.packDao().getPack(packId)!!
        assertEquals(4, acknowledged.whatsappSyncedDataVersion)

        database.packDao().upsert(acknowledged.copy(imageDataVersion = 5))
        database.packDao().setWhatsappAdded(packId, false)
        assertFalse(repository.acknowledgeWhitelistedWhatsappInstall(packId, expectedRevision = 4))
        val edited = database.packDao().getPack(packId)!!
        assertTrue(edited.whatsappAdded)
        assertEquals(4, edited.whatsappSyncedDataVersion)

        assertTrue(repository.acknowledgeWhitelistedWhatsappInstall(packId, expectedRevision = 5))
        assertEquals(5, database.packDao().getPack(packId)!!.whatsappSyncedDataVersion)

        database.packDao().setWhatsappAdded(packId, false)
        assertFalse(database.packDao().getPack(packId)!!.whatsappAdded)
    }

    @Test
    fun combinedPublishBumpAndTelegramAcknowledgementCommitTogether() = runBlocking {
        val finalizedRevision = repository.finalizePackReady(
            packId = packId,
            isAnimated = false,
            trayIconPath = null,
            bumpContentRevision = true,
            expectedRevision = 4,
            acknowledgeTelegram = true,
        )

        assertEquals(5, finalizedRevision)
        val finalized = database.packDao().getPack(packId)!!
        assertEquals(5, finalized.imageDataVersion)
        assertEquals(5, finalized.telegramSyncedDataVersion)

        val raced = finalized.copy(imageDataVersion = 6)
        database.packDao().upsert(raced)
        assertNull(
            repository.finalizePackReady(
                packId = packId,
                isAnimated = false,
                trayIconPath = null,
                bumpContentRevision = true,
                expectedRevision = 5,
                acknowledgeTelegram = true,
            ),
        )
        assertEquals(raced, database.packDao().getPack(packId))
    }

    @Test
    fun firstTelegramMarkerCommitsWithSetIdentityAndRepresentedRevision() = runBlocking {
        val pack = database.packDao().getPack(packId)!!
        database.packDao().upsert(
            pack.copy(
                telegramSetName = null,
                telegramSyncedDataVersion = null,
            ),
        )
        val rowId = database.stickerDao().upsert(
            StickerEntity(
                packId = packId,
                remoteId = null,
                position = 0,
                emojis = "🙂",
                sniffedContentType = "image/png",
                sourceLocalUri = null,
                isVideo = false,
                originalFilePath = null,
                convertedWhatsappPath = null,
                convertedTelegramPath = null,
                conversionStatus = "Done",
                conversionError = null,
            ),
        )

        assertTrue(
            repository.persistTelegramPushSuccess(
                packId = packId,
                rowId = rowId,
                convertedPath = "/telegram/$rowId.webp",
                fullSetName = "freshness_test_by_bot",
                representedRevision = 4,
            ),
        )
        val linked = database.packDao().getPack(packId)!!
        val marked = database.stickerDao().findByRowId(rowId)!!
        assertEquals("freshness_test_by_bot", linked.telegramSetName)
        assertEquals(4, linked.telegramSyncedDataVersion)
        assertEquals("/telegram/$rowId.webp", marked.convertedTelegramPath)

        database.stickerDao().deleteByRowId(rowId)
        database.packDao().upsert(
            linked.copy(
                telegramSetName = null,
                telegramSyncedDataVersion = null,
                imageDataVersion = 5,
            ),
        )
        assertFalse(
            repository.persistTelegramPushSuccess(
                packId = packId,
                rowId = rowId,
                convertedPath = "/telegram/missing.webp",
                fullSetName = "freshness_test_by_bot",
                representedRevision = 4,
            ),
        )
        val raced = database.packDao().getPack(packId)!!
        assertEquals("freshness_test_by_bot", raced.telegramSetName)
        assertEquals(4, raced.telegramSyncedDataVersion)
        assertEquals(
            TelegramFreshnessState.OutOfDate,
            deriveTelegramFreshness(
                origin = PackOrigin.Created,
                imageDataVersion = raced.imageDataVersion,
                syncedDataVersion = raced.telegramSyncedDataVersion,
                hasTelegramSet = true,
                pushedStickerCount = 0,
                totalStickerCount = 3,
            ),
        )
    }
}
