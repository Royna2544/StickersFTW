package com.royna.stickersftw.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.royna.stickersftw.data.local.AppDatabase
import com.royna.stickersftw.data.local.PackDao
import com.royna.stickersftw.data.local.PackEntity
import com.royna.stickersftw.data.local.StickerDao
import com.royna.stickersftw.data.local.StickerEntity
import java.io.File

/** WhatsApp's third-party sticker contract: a read-only ContentProvider
 * exposing /metadata, /metadata/<id>, /stickers/<id>, and
 * /stickers_asset/<id>/<file>. Backed by blocking (non-suspend) Room reads --
 * safe here because WhatsApp calls this provider from Binder pool threads in
 * its own process, never this process's main thread, matching WhatsApp's own
 * sample provider's synchronous SQLite reads. */
class StickerContentProvider : ContentProvider() {
    private lateinit var packDao: PackDao
    private lateinit var stickerDao: StickerDao
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val context = context ?: return false
        val database = AppDatabase.getInstance(context)
        packDao = database.packDao()
        stickerDao = database.stickerDao()

        val authority = WhatsAppContract.authorityFor(context)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", METADATA)
            addURI(authority, "metadata/*", METADATA_FOR_PACK)
            addURI(authority, "stickers/*", STICKERS)
            addURI(authority, "stickers_asset/*/*", STICKERS_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (matcher.match(uri)) {
        METADATA -> metadataCursor(packDao.getReadyPacksBlocking())
        METADATA_FOR_PACK -> {
            val id = uri.lastPathSegment
            val pack = id?.let { packDao.getReadyPackBlocking(it) }
            metadataCursor(if (pack != null) listOf(pack) else emptyList())
        }
        STICKERS -> {
            val id = uri.lastPathSegment
            stickersCursor(if (id != null) stickerDao.getStickersBlocking(id) else emptyList())
        }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKERS_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size < 3) return null

        val packId = segments[1]
        val fileName = segments[2]
        val baseDir = context?.filesDir ?: return null
        val file = if (fileName == WhatsAppContract.TRAY_ICON_FILE_NAME) {
            File(baseDir, "packs/$packId/${WhatsAppContract.TRAY_ICON_FILE_NAME}")
        } else {
            File(baseDir, "packs/$packId/converted/$fileName")
        }
        if (!file.exists()) return null

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("This provider is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("This provider is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("This provider is read-only.")

    private fun metadataCursor(packs: List<PackEntity>): Cursor {
        val cursor = MatrixCursor(WhatsAppContract.Metadata.ALL_COLUMNS)
        for (pack in packs) {
            cursor.addRow(
                arrayOf<Any?>(
                    pack.id,
                    pack.title,
                    pack.publisher,
                    WhatsAppContract.TRAY_ICON_FILE_NAME,
                    null, // android_play_store_link
                    null, // ios_app_download_link
                    null, // sticker_pack_publisher_email
                    null, // sticker_pack_publisher_website
                    null, // sticker_pack_privacy_policy_website
                    null, // sticker_pack_license_agreement_website
                    // Caching is left on (below), so this is the only thing
                    // that tells WhatsApp a pack's files have changed.
                    pack.imageDataVersion.toString(),
                    0, // whatsapp_will_not_cache_stickers (0 = caching allowed)
                    if (pack.isAnimatedPack) 1 else 0,
                ),
            )
        }
        return cursor
    }

    private fun stickersCursor(stickers: List<StickerEntity>): Cursor {
        val cursor = MatrixCursor(WhatsAppContract.Stickers.ALL_COLUMNS)
        for (sticker in stickers) {
            val fileName = sticker.convertedWhatsappPath?.let { File(it).name } ?: continue
            cursor.addRow(arrayOf<Any?>(fileName, sticker.emojis, null))
        }
        return cursor
    }

    companion object {
        private const val METADATA = 1
        private const val METADATA_FOR_PACK = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4
    }
}
