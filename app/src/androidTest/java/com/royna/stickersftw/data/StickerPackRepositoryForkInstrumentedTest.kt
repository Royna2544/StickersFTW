package com.royna.stickersftw.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.data.local.AppDatabase
import com.royna.stickersftw.data.local.PackEntity
import com.royna.stickersftw.data.local.StickerEntity
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerPackRepositoryForkInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = AppDatabase.getInstance(context)
    private val repository = StickerPackRepository(context)
    private val sourcePackId = "fork-source-${UUID.randomUUID()}"
    private val sourceDir by lazy { File(context.filesDir, "packs/$sourcePackId") }
    private val createdPackIds = mutableSetOf<String>()
    private lateinit var sourceRowIds: List<Long>

    @Before
    fun seedSourcePack() {
        runBlocking {
            val now = System.currentTimeMillis()
            var pack = PackEntity(
                id = sourcePackId,
                origin = PackOrigin.Imported.name,
                telegramSetName = "upstream_by_sourcebot",
                pushShortName = "old_push_name",
                sourceUrl = "https://t.me/addstickers/upstream",
                title = "Upstream",
                publisher = "@sourcebot",
                stickerCount = 3,
                isAnimatedPack = true,
                status = PackStatus.Ready.name,
                errorMessage = null,
                warningMessage = "Old import warning",
                trayIconPath = null,
                isPinned = true,
                whatsappAdded = true,
                createdAtMillis = now - 10_000,
                updatedAtMillis = now - 5_000,
                sourceSignature = "upstream-signature",
                updateAvailable = true,
                updateCheckEnabled = true,
                importPartIndex = 2,
                conversionBias = "Sharpness",
                imageDataVersion = 11,
                whatsappSyncedDataVersion = 11,
                telegramSyncedDataVersion = 11,
            )
            database.packDao().upsert(pack)

            sourceRowIds = (0 until 3).map { position ->
                val original = File(sourceDir, "original/source-$position.bin")
                val whatsapp = File(sourceDir, "converted/source-$position.webp")
                val telegram = File(sourceDir, "telegram/source-$position.bin")
                writeAsset(original, 10 + position)
                writeAsset(whatsapp, 30 + position)
                writeAsset(telegram, 50 + position)
                database.stickerDao().upsert(
                    StickerEntity(
                        packId = sourcePackId,
                        remoteId = "remote-$position",
                        position = position,
                        emojis = if (position == 1) "🎬,✨" else "🙂",
                        sniffedContentType = when (position) {
                            1 -> "video/webm"
                            2 -> "application/x-tgsticker"
                            else -> "image/webp"
                        },
                        sourceLocalUri = Uri.fromFile(original).toString(),
                        isVideo = position == 1,
                        originalFilePath = original.absolutePath,
                        convertedWhatsappPath = whatsapp.absolutePath,
                        convertedTelegramPath = telegram.absolutePath,
                        conversionStatus = "Done",
                        conversionError = null,
                        trimStartMs = if (position == 1) 1_250L else 0L,
                        trimDurationMs = if (position == 1) 2_750L else 0L,
                        cropLeft = 0.1f.takeIf { position == 0 },
                        cropTop = 0.2f.takeIf { position == 0 },
                        cropRight = 0.8f.takeIf { position == 0 },
                        cropBottom = 0.9f.takeIf { position == 0 },
                    ),
                )
            }

            val tray = File(sourceDir, "tray.webp")
            writeAsset(tray, 90)
            pack = pack.copy(
                trayIconPath = tray.absolutePath,
                trayStickerRowId = sourceRowIds[1],
            )
            database.packDao().upsert(pack)
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            createdPackIds.forEach { database.packDao().delete(it) }
            database.packDao().delete(sourcePackId)
        }
        createdPackIds.forEach { File(context.filesDir, "packs/$it").deleteRecursively() }
        sourceDir.deleteRecursively()
    }

    @Test
    fun forkCopiesOwnedAssetsMapsRowsAndResetsEveryRemoteLink() = runBlocking {
        val sourcePackBefore = database.packDao().getPack(sourcePackId)!!
        val sourceRowsBefore = database.stickerDao().getStickersOnce(sourcePackId)
            .sortedBy { it.position }
        val sourceBytes = sourceRowsBefore.associate { row ->
            row.rowId to Pair(
                File(row.originalFilePath!!).readBytes(),
                File(row.convertedWhatsappPath!!).readBytes(),
            )
        }
        val sourceTrayBytes = File(sourcePackBefore.trayIconPath!!).readBytes()

        val result = repository.forkPackForLocalEdits(sourcePackId, "  Upstream (Remix)  ")!!
        createdPackIds += result.newPackId

        assertNotEquals(sourcePackId, result.newPackId)
        assertEquals(sourceRowIds.toSet(), result.rowIdMap.keys)
        assertEquals(sourceRowIds.size, result.rowIdMap.values.toSet().size)
        assertTrue(result.rowIdMap.values.none { it in sourceRowIds })
        assertEquals(sourcePackBefore, database.packDao().getPack(sourcePackId))
        assertEquals(sourceRowsBefore, database.stickerDao().getStickersOnce(sourcePackId).sortedBy { it.position })

        val fork = database.packDao().getPack(result.newPackId)!!
        assertEquals(PackOrigin.Created.name, fork.origin)
        assertEquals("Upstream (Remix)", fork.title)
        assertEquals("You", fork.publisher)
        assertEquals(PackStatus.Ready.name, fork.status)
        assertEquals(3, fork.stickerCount)
        assertTrue(fork.isAnimatedPack)
        assertEquals("Sharpness", fork.conversionBias)
        assertEquals(1, fork.imageDataVersion)
        assertEquals(result.rowIdMap.getValue(sourceRowIds[1]), fork.trayStickerRowId)
        assertNull(fork.telegramSetName)
        assertNull(fork.pushShortName)
        assertNull(fork.sourceUrl)
        assertNull(fork.sourceSignature)
        assertFalse(fork.updateAvailable)
        assertFalse(fork.updateCheckEnabled)
        assertEquals(0, fork.importPartIndex)
        assertFalse(fork.isPinned)
        assertFalse(fork.whatsappAdded)
        assertNull(fork.whatsappSyncedDataVersion)
        assertNull(fork.telegramSyncedDataVersion)
        assertNull(fork.warningMessage)
        assertNull(fork.errorMessage)

        val forkRoot = File(context.filesDir, "packs/${result.newPackId}").canonicalFile
        val forkPrefix = forkRoot.path + File.separator
        val forkTray = File(fork.trayIconPath!!).canonicalFile
        assertTrue(forkTray.path.startsWith(forkPrefix))
        assertArrayEquals(sourceTrayBytes, forkTray.readBytes())
        assertNotEquals(File(sourcePackBefore.trayIconPath).canonicalPath, forkTray.path)

        val forkRows = database.stickerDao().getStickersOnce(result.newPackId)
            .associateBy { it.rowId }
        sourceRowsBefore.forEach { sourceRow ->
            val forkRow = forkRows.getValue(result.rowIdMap.getValue(sourceRow.rowId))
            assertEquals(sourceRow.position, forkRow.position)
            assertEquals(sourceRow.emojis, forkRow.emojis)
            assertEquals(sourceRow.sniffedContentType, forkRow.sniffedContentType)
            assertEquals(sourceRow.isVideo, forkRow.isVideo)
            assertEquals(sourceRow.conversionStatus, forkRow.conversionStatus)
            assertEquals(sourceRow.conversionError, forkRow.conversionError)
            assertEquals(sourceRow.trimStartMs, forkRow.trimStartMs)
            assertEquals(sourceRow.trimDurationMs, forkRow.trimDurationMs)
            assertEquals(sourceRow.cropLeft, forkRow.cropLeft)
            assertEquals(sourceRow.cropTop, forkRow.cropTop)
            assertEquals(sourceRow.cropRight, forkRow.cropRight)
            assertEquals(sourceRow.cropBottom, forkRow.cropBottom)
            assertNull(forkRow.remoteId)
            assertNull(forkRow.convertedTelegramPath)

            val forkOriginal = File(forkRow.originalFilePath!!).canonicalFile
            val forkWhatsapp = File(forkRow.convertedWhatsappPath!!).canonicalFile
            assertTrue(forkOriginal.path.startsWith(forkPrefix))
            assertTrue(forkWhatsapp.path.startsWith(forkPrefix))
            assertNotEquals(File(sourceRow.originalFilePath!!).canonicalPath, forkOriginal.path)
            assertNotEquals(File(sourceRow.convertedWhatsappPath!!).canonicalPath, forkWhatsapp.path)
            val forkSourceUri = Uri.parse(requireNotNull(forkRow.sourceLocalUri))
            assertEquals("file", forkSourceUri.scheme)
            val forkSourceFile = File(requireNotNull(forkSourceUri.path)).canonicalFile
            // Android may spell the same app-private inode through either
            // /data/data or /data/user/0. Compare canonical files instead of
            // requiring those equivalent URI strings to use one alias.
            assertEquals(forkOriginal.path, forkSourceFile.path)
            assertTrue(forkSourceFile.path.startsWith(forkPrefix))
            assertNotEquals(sourceRow.sourceLocalUri, forkRow.sourceLocalUri)
            assertArrayEquals(sourceBytes.getValue(sourceRow.rowId).first, forkOriginal.readBytes())
            assertArrayEquals(sourceBytes.getValue(sourceRow.rowId).second, forkWhatsapp.readBytes())
        }

        val firstSource = sourceRowsBefore.first()
        val firstFork = forkRows.getValue(result.rowIdMap.getValue(firstSource.rowId))
        File(firstFork.originalFilePath!!).writeBytes(byteArrayOf(1, 2, 3))
        assertArrayEquals(sourceBytes.getValue(firstSource.rowId).first, File(firstSource.originalFilePath!!).readBytes())
        assertTrue(repository.updateStickerEmojis(result.newPackId, firstFork.rowId, listOf("🔥")))
        assertEquals(firstSource.emojis, database.stickerDao().findByRowId(firstSource.rowId)!!.emojis)
        assertEquals("🔥", database.stickerDao().findByRowId(firstFork.rowId)!!.emojis)
        assertFalse(repository.discardUnmodifiedLocalRemix(result.newPackId))
    }

    @Test
    fun unmodifiedForkCanBeReclaimedAfterReplayCannotStart() = runBlocking {
        val sourceBefore = database.packDao().getPack(sourcePackId)!!
        val result = repository.forkPackForLocalEdits(sourcePackId, "Abandoned Remix")!!
        val forkDirectory = File(context.filesDir, "packs/${result.newPackId}")

        assertTrue(forkDirectory.isDirectory)
        assertTrue(repository.discardUnmodifiedLocalRemix(result.newPackId))
        assertNull(database.packDao().getPack(result.newPackId))
        assertFalse(forkDirectory.exists())
        assertEquals(sourceBefore, database.packDao().getPack(sourcePackId))
    }

    @Test
    fun missingReferencedAssetCleansPartiallyStagedForkAndLeavesDatabaseUntouched() = runBlocking {
        val broken = database.stickerDao().findByRowId(sourceRowIds[1])!!
        File(broken.convertedWhatsappPath!!).delete()
        val sourcePackBefore = database.packDao().getPack(sourcePackId)!!
        val sourceRowsBefore = database.stickerDao().getStickersOnce(sourcePackId)
            .sortedBy { it.position }
        val packsRoot = File(context.filesDir, "packs")
        val directoriesBefore = packsRoot.listFiles().orEmpty().map { it.name }.toSet()
        val readyPackIdsBefore = database.packDao().getReadyPacksBlocking().map { it.id }.toSet()

        val result = repository.forkPackForLocalEdits(sourcePackId, "Broken Remix")

        assertNull(result)
        assertEquals(sourcePackBefore, database.packDao().getPack(sourcePackId))
        assertEquals(sourceRowsBefore, database.stickerDao().getStickersOnce(sourcePackId).sortedBy { it.position })
        assertEquals(directoriesBefore, packsRoot.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(readyPackIdsBefore, database.packDao().getReadyPacksBlocking().map { it.id }.toSet())
    }

    private fun writeAsset(file: File, marker: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(marker.toByte(), 7, 8, 9))
    }
}
