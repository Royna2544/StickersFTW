package com.royna.stickersftw.data

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.data.local.AppDatabase
import com.royna.stickersftw.data.local.PackEntity
import com.royna.stickersftw.data.local.StickerEntity
import com.royna.stickersftw.data.model.PackOperationProgress
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.network.TelegramBackendConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerPackRepositoryEditorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = AppDatabase.getInstance(context)
    private val repository = StickerPackRepository(context)
    private val packId = "editor-test-${UUID.randomUUID()}"
    private val packDir by lazy { File(context.filesDir, "packs/$packId") }
    private lateinit var rowIds: List<Long>

    @Before
    fun seedPack() {
        runBlocking {
            val now = System.currentTimeMillis()
            var pack = PackEntity(
            id = packId,
            origin = PackOrigin.Created.name,
            telegramSetName = "editor_test_by_bot",
            pushShortName = "editor_test",
            sourceUrl = null,
            title = "Editor test",
            publisher = "Tester",
            stickerCount = 4,
            isAnimatedPack = false,
            status = PackStatus.Ready.name,
            errorMessage = null,
            warningMessage = null,
            trayIconPath = null,
            isPinned = false,
            whatsappAdded = true,
            createdAtMillis = now,
            updatedAtMillis = now,
            imageDataVersion = 5,
            whatsappSyncedDataVersion = 5,
            telegramSyncedDataVersion = 5,
        )
        database.packDao().upsert(pack)

        rowIds = (0 until 4).map { position ->
            val inserted = database.stickerDao().upsert(
                StickerEntity(
                    packId = packId,
                    remoteId = null,
                    position = position,
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
            val original = File(packDir, "original/$inserted.png")
            val whatsapp = File(packDir, "converted/$inserted.webp")
            val telegram = File(packDir, "telegram/$inserted.webp")
            writePng(original, Color.rgb(30 + position * 30, 70, 120))
            whatsapp.parentFile?.mkdirs()
            whatsapp.writeBytes(byteArrayOf(position.toByte(), 1, 2, 3))
            telegram.parentFile?.mkdirs()
            telegram.writeBytes(byteArrayOf(position.toByte(), 4, 5, 6))
            database.stickerDao().upsert(
                database.stickerDao().findByRowId(inserted)!!.copy(
                    sourceLocalUri = Uri.fromFile(original).toString(),
                    originalFilePath = original.absolutePath,
                    convertedWhatsappPath = whatsapp.absolutePath,
                    convertedTelegramPath = telegram.absolutePath,
                ),
            )
            inserted
        }

        val tray = File(packDir, "tray.webp")
        writePng(tray, Color.BLUE)
            pack = pack.copy(trayIconPath = tray.absolutePath, trayStickerRowId = rowIds.first())
            database.packDao().upsert(pack)
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            repository.finalizeLastPackEdit(packId)
            database.packDao().delete(packId)
            packDir.deleteRecursively()
        }
    }

    @Test
    fun reorderAndUndoRestoreContentWithMonotonicSyncedRevision() = runBlocking {
        assertEquals(
            5,
            repository.observePacks().first().first { it.id == packId }.imageDataVersion,
        )
        assertTrue(repository.reorderStickers(packId, rowIds.reversed()))
        assertEquals(
            rowIds.reversed(),
            database.stickerDao().getStickersOnce(packId).sortedBy { it.position }.map { it.rowId },
        )
        assertEquals(6, database.packDao().getPack(packId)!!.imageDataVersion)

        repository.undoLastPackEdit(packId)

        assertEquals(
            rowIds,
            database.stickerDao().getStickersOnce(packId).sortedBy { it.position }.map { it.rowId },
        )
        val restored = database.packDao().getPack(packId)!!
        assertEquals(7, restored.imageDataVersion)
        assertEquals(7, restored.whatsappSyncedDataVersion)
        assertEquals(7, restored.telegramSyncedDataVersion)
    }

    @Test
    fun legacyPartialPushBecomesStaleOnEditAndAlignedOnUndo() = runBlocking {
        val pack = database.packDao().getPack(packId)!!
        database.packDao().upsert(pack.copy(telegramSyncedDataVersion = null))
        val missing = database.stickerDao().findByRowId(rowIds.last())!!
        database.stickerDao().upsert(missing.copy(convertedTelegramPath = null))
        assertNull(database.packDao().getPack(packId)!!.telegramSyncedDataVersion)

        assertTrue(repository.reorderStickers(packId, rowIds.reversed()))
        val edited = database.packDao().getPack(packId)!!
        assertEquals(6, edited.imageDataVersion)
        assertEquals(5, edited.telegramSyncedDataVersion)

        repository.undoLastPackEdit(packId)
        val restored = database.packDao().getPack(packId)!!
        assertEquals(7, restored.imageDataVersion)
        assertEquals(7, restored.telegramSyncedDataVersion)
    }

    @Test
    fun staleTelegramPackIsRejectedBeforeRemoteMutation() = runBlocking {
        val pack = database.packDao().getPack(packId)!!
        val stale = pack.copy(imageDataVersion = 6, telegramSyncedDataVersion = 5)
        database.packDao().upsert(stale)

        val progress = repository.publishPack(
            packId = packId,
            pushToTelegram = true,
            addToWhatsapp = false,
            backendConfig = TelegramBackendConfig.ServerUrl("http://127.0.0.1:1"),
            telegramUserId = "unused",
        ).toList()

        assertTrue(progress.single() is PackOperationProgress.Failed)
        assertEquals(stale, database.packDao().getPack(packId))
    }

    @Test
    fun deleteUndoAndFinalizationRespectMinimumAndFileLifetime() = runBlocking {
        val targetId = rowIds.first()
        val target = database.stickerDao().findByRowId(targetId)!!
        val original = File(target.originalFilePath!!)

        assertTrue(repository.deleteSticker(packId, targetId))
        assertTrue(original.exists())
        assertEquals(3, database.packDao().getPack(packId)!!.stickerCount)
        assertEquals(null, database.packDao().getPack(packId)!!.trayStickerRowId)

        repository.undoLastPackEdit(packId)
        assertTrue(original.exists())
        assertEquals(4, database.packDao().getPack(packId)!!.stickerCount)
        assertEquals(targetId, database.packDao().getPack(packId)!!.trayStickerRowId)

        assertTrue(repository.deleteSticker(packId, targetId))
        assertFalse(repository.deleteSticker(packId, rowIds[1]))
        repository.finalizeLastPackEdit(packId)
        assertFalse(original.exists())
    }

    @Test
    fun failedEditIsUnchangedAndSuccessfulEditSwapsOnlyTheTarget() = runBlocking {
        val targetId = rowIds.first()
        val beforePack = database.packDao().getPack(packId)!!
        val beforeTarget = database.stickerDao().findByRowId(targetId)!!
        val beforeOther = database.stickerDao().findByRowId(rowIds[1])!!

        val failed = repository.editSticker(
            packId,
            targetId,
            PickedMediaItem("file:///definitely-missing.png", PickedMediaKind.Image),
        ).toList()

        assertTrue(failed.last() is PackOperationProgress.Failed)
        assertEquals(beforePack, database.packDao().getPack(packId))
        assertEquals(beforeTarget, database.stickerDao().findByRowId(targetId))

        val replacement = File(context.cacheDir, "replacement-${UUID.randomUUID()}.png")
        writePng(replacement, Color.MAGENTA)
        try {
            val completed = repository.editSticker(
                packId,
                targetId,
                PickedMediaItem(Uri.fromFile(replacement).toString(), PickedMediaKind.Image),
            ).toList()

            assertTrue(completed.last() is PackOperationProgress.Complete)
            val afterPack = database.packDao().getPack(packId)!!
            val afterTarget = database.stickerDao().findByRowId(targetId)!!
            assertEquals(6, afterPack.imageDataVersion)
            assertNotEquals(beforeTarget.originalFilePath, afterTarget.originalFilePath)
            assertNotEquals(beforeTarget.convertedWhatsappPath, afterTarget.convertedWhatsappPath)
            assertNotEquals(beforePack.trayIconPath, afterPack.trayIconPath)
            assertTrue(File(afterTarget.originalFilePath!!).exists())
            assertTrue(File(afterTarget.convertedWhatsappPath!!).exists())
            assertTrue(File(afterPack.trayIconPath!!).exists())
            assertFalse(File(beforeTarget.originalFilePath!!).exists())
            assertFalse(File(beforeTarget.convertedWhatsappPath!!).exists())
            assertFalse(File(beforePack.trayIconPath!!).exists())
            assertEquals(beforeOther, database.stickerDao().findByRowId(rowIds[1]))
        } finally {
            replacement.delete()
        }
    }

    private fun writePng(file: File, color: Int) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
