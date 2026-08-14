package com.royna.stickersftw.whatsapp

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** WhatsApp caches a pack's assets against `image_data_version` and re-reads
 * them only when it changes, so a pack whose stickers changed while the value
 * stayed put keeps serving the old set -- which looks like the write failed
 * rather than the cache holding. This pins the provider to the stored value
 * instead of the constant it used to report for every pack. */
@RunWith(AndroidJUnit4::class)
class StickerContentProviderVersionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = AppDatabase.getInstance(context)
    private val packId = "test-${UUID.randomUUID()}"

    private fun packRow(version: Int, trayIconPath: String? = null) = PackEntity(
        id = packId,
        origin = PackOrigin.Created.name,
        telegramSetName = null,
        pushShortName = null,
        sourceUrl = null,
        title = "Version probe",
        publisher = "test",
        stickerCount = 1,
        isAnimatedPack = false,
        status = PackStatus.Ready.name,
        errorMessage = null,
        warningMessage = null,
        trayIconPath = trayIconPath,
        isPinned = false,
        whatsappAdded = false,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        imageDataVersion = version,
    )

    @Before
    fun seed() {
        runBlocking {
            database.packDao().upsert(packRow(version = 7))
            // The provider skips stickers with no file on disk, so give it one
            // that resolves; otherwise the pack has no rows to serve.
            val file = File(context.filesDir, "packs/$packId/converted/probe.webp")
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(4))
            database.stickerDao().upsert(
                StickerEntity(
                    packId = packId,
                    remoteId = null,
                    position = 0,
                    emojis = "🙂",
                    sniffedContentType = null,
                    sourceLocalUri = null,
                    isVideo = false,
                    originalFilePath = null,
                    convertedWhatsappPath = file.absolutePath,
                    convertedTelegramPath = null,
                    conversionStatus = "Done",
                    conversionError = null,
                ),
            )
        }
    }

    @After
    fun cleanUp() {
        runBlocking {
            database.stickerDao().deleteForPack(packId)
            database.packDao().delete(packId)
            File(context.filesDir, "packs/$packId").deleteRecursively()
        }
    }

    private fun readVersion(): String? {
        val uri = Uri.parse("content://${WhatsAppContract.authorityFor(context)}/metadata/$packId")
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            assertNotNull("provider returned no cursor", cursor)
            if (cursor == null || !cursor.moveToFirst()) return null
            val column = cursor.getColumnIndexOrThrow(WhatsAppContract.Metadata.IMAGE_DATA_VERSION)
            return cursor.getString(column)
        }
    }

    @Test
    fun reportsTheStoredVersionRatherThanAConstant() {
        assertEquals("7", readVersion())
    }

    @Test
    fun changingTheStoredVersionChangesWhatWhatsappSees() {
        assertEquals("7", readVersion())
        runBlocking { database.packDao().upsert(packRow(version = 8)) }
        assertEquals("8", readVersion())
    }

    @Test
    fun trayRequestReadsTheVersionedStoredPath() {
        val legacy = File(context.filesDir, "packs/$packId/${WhatsAppContract.TRAY_ICON_FILE_NAME}")
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(byteArrayOf(1, 1, 1))
        val versioned = File(context.filesDir, "packs/$packId/tray-edit.webp")
        versioned.writeBytes(byteArrayOf(7, 8, 9, 10))
        runBlocking {
            database.packDao().upsert(packRow(version = 8, trayIconPath = versioned.absolutePath))
        }
        val uri = Uri.parse(
            "content://${WhatsAppContract.authorityFor(context)}/stickers_asset/" +
                "$packId/${WhatsAppContract.TRAY_ICON_FILE_NAME}",
        )

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

        assertArrayEquals(versioned.readBytes(), bytes)
    }
}
