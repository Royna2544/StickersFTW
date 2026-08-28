package com.royna.stickersftw.data.local

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.BuildConfig
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.data.SourceSignature
import com.royna.stickersftw.data.model.PackOperationProgress
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.TelegramFreshnessState
import com.royna.stickersftw.model.deriveTelegramFreshness
import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
        File(context.filesDir, "packs/$packId").deleteRecursively()
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
    fun successfulFullImportStampsBuildAndClearsReconversionNeed() = runBlocking {
        val initial = database.packDao().getPack(packId)!!
        database.packDao().upsert(
            initial.copy(
                origin = PackOrigin.Imported.name,
                convertedAppVersionCode = null,
                convertedAppVersionName = null,
            ),
        )

        val historical = repository.observePacks().first().first { it.id == packId }
        assertTrue(historical.needsReconversion)

        val finalizedRevision = repository.finalizePackReady(
            packId = packId,
            isAnimated = false,
            trayIconPath = null,
            expectedRevision = 4,
            convertedAppVersionCode = BuildConfig.VERSION_CODE,
            convertedAppVersionName = BuildConfig.VERSION_NAME,
        )

        assertEquals(5, finalizedRevision)
        val finalized = database.packDao().getPack(packId)!!
        assertEquals(5, finalized.imageDataVersion)
        assertEquals(BuildConfig.VERSION_CODE, finalized.convertedAppVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, finalized.convertedAppVersionName)

        val current = repository.observePacks().first().first { it.id == packId }
        assertEquals(BuildConfig.VERSION_CODE, current.convertedAppVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, current.convertedAppVersionName)
        assertFalse(current.needsReconversion)
    }

    @Test
    fun equivalentLegacyRefreshBackfillsStableIdentityAndRejectsStaleSnapshot() = runBlocking {
        val initial = database.packDao().getPack(packId)!!
        val dto = StickerSetDto(
            name = "freshness_test_by_bot",
            title = "Freshness test",
            stickers = listOf(
                StickerDto(
                    id = "download-locator",
                    stableId = "stable-file-identity",
                    width = 512,
                    height = 512,
                    size = 100,
                    emoji = "🙂",
                ),
            ),
        )
        val legacySignature = SourceSignature.compute(
            dto.copy(stickers = dto.stickers.map { it.copy(stableId = null) }),
        )
        val snapshot = initial.copy(
            origin = PackOrigin.Imported.name,
            sourceSignature = legacySignature,
        )
        database.packDao().upsert(snapshot)
        val rowId = database.stickerDao().upsert(
            StickerEntity(
                packId = packId,
                remoteId = "download-locator",
                position = 0,
                emojis = "🙂",
                sniffedContentType = "image/webp",
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
            repository.markSourceCurrentIfUnchanged(
                snapshot,
                dto,
                SourceSignature.compute(dto),
            ),
        )
        assertEquals(
            "stable-file-identity",
            database.stickerDao().findByRowId(rowId)!!.remoteStableId,
        )

        val refreshed = database.packDao().getPack(packId)!!
        database.packDao().upsert(refreshed.copy(imageDataVersion = refreshed.imageDataVersion + 1))
        assertFalse(
            repository.markSourceCurrentIfUnchanged(
                refreshed,
                dto.copy(title = "stale response"),
                "must-not-win",
            ),
        )
        assertEquals(SourceSignature.compute(dto), database.packDao().getPack(packId)!!.sourceSignature)

        database.stickerDao().deleteByRowId(rowId)
    }

    @Test
    fun cachedReconversionAtomicallyBumpsRevisionAndStampsBuild() = runBlocking {
        val (startingPack, startingRows) = seedImportedAssets()

        val progress = repository.reconvertImportedPack(packId, ConversionBias.Auto).toList()

        assertTrue(progress.last() is PackOperationProgress.Complete)
        val convertedPack = database.packDao().getPack(packId)!!
        val convertedRows = database.stickerDao().getStickersOnce(packId)
        assertEquals(startingPack.imageDataVersion + 1, convertedPack.imageDataVersion)
        assertEquals(startingPack.whatsappSyncedDataVersion, convertedPack.whatsappSyncedDataVersion)
        assertEquals(BuildConfig.VERSION_CODE, convertedPack.convertedAppVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, convertedPack.convertedAppVersionName)
        assertTrue(convertedRows.all { File(requireNotNull(it.convertedWhatsappPath)).isFile })
        assertTrue(
            convertedRows.zip(startingRows).all { (converted, starting) ->
                converted.convertedWhatsappPath != starting.convertedWhatsappPath
            },
        )
        // Old versioned paths deliberately remain valid for provider opens
        // that raced the atomic database swap.
        assertTrue(startingRows.all { File(requireNotNull(it.convertedWhatsappPath)).isFile })
    }

    @Test
    fun failedCachedReconversionKeepsExistingRowsRevisionAndFiles() = runBlocking {
        val (startingPack, startingRows) = seedImportedAssets(missingOriginalAt = 2)

        val progress = repository.reconvertImportedPack(packId, ConversionBias.Auto).toList()

        assertTrue(progress.last() is PackOperationProgress.Failed)
        assertEquals(startingPack, database.packDao().getPack(packId))
        assertEquals(startingRows, database.stickerDao().getStickersOnce(packId))
        assertTrue(startingRows.all { File(requireNotNull(it.convertedWhatsappPath)).isFile })
        assertTrue(
            File(context.filesDir, "packs/$packId/converted")
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith("reconvert-") },
        )
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

    private suspend fun seedImportedAssets(
        missingOriginalAt: Int? = null,
    ): Pair<PackEntity, List<StickerEntity>> {
        val packDir = File(context.filesDir, "packs/$packId")
        val originalDir = File(packDir, "original").apply { mkdirs() }
        val convertedDir = File(packDir, "converted").apply { mkdirs() }
        fun writePng(file: File, color: Int) {
            file.parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }

        val seededRows = (0 until 3).map { index ->
            val original = File(originalDir, "$index.png")
            if (index != missingOriginalAt) writePng(original, Color.rgb(40 * index, 80, 160))
            val previousOutput = File(convertedDir, "old-$index.webp")
            writePng(previousOutput, Color.rgb(40 * index, 80, 160))
            val rowId = database.stickerDao().upsert(
                StickerEntity(
                    packId = packId,
                    remoteId = "file-$index",
                    remoteStableId = "stable-$index",
                    position = index,
                    emojis = "🙂",
                    sniffedContentType = "image/png",
                    sourceLocalUri = null,
                    isVideo = false,
                    originalFilePath = original.absolutePath,
                    convertedWhatsappPath = previousOutput.absolutePath,
                    convertedTelegramPath = null,
                    conversionStatus = "Done",
                    conversionError = null,
                ),
            )
            database.stickerDao().findByRowId(rowId)!!
        }
        val tray = File(packDir, "old-tray.webp").also { writePng(it, Color.BLUE) }
        val current = database.packDao().getPack(packId)!!
        val imported = current.copy(
            origin = PackOrigin.Imported.name,
            telegramSetName = "freshness_test_by_bot",
            sourceUrl = "https://t.me/addstickers/freshness_test_by_bot",
            stickerCount = seededRows.size,
            trayIconPath = tray.absolutePath,
            trayStickerRowId = seededRows.first().rowId,
            whatsappAdded = true,
            whatsappSyncedDataVersion = current.imageDataVersion,
            convertedAppVersionCode = null,
            convertedAppVersionName = null,
        )
        database.packDao().upsert(imported)
        return imported to seededRows
    }
}
