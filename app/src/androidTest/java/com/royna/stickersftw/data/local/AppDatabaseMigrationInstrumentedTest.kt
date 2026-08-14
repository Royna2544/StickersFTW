package com.royna.stickersftw.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-6-7-test.db"
    private var roomDatabase: AppDatabase? = null

    @Before
    fun cleanBefore() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanAfter() {
        roomDatabase?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationSixToSevenPreservesRowsAndBackfillsRevisionAnchors() {
        createVersionSixDatabase()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .build()
            .also { roomDatabase = it }

        val (full, partial, imported) = runBlocking {
            Triple(
                migrated.packDao().getPack("created-full")!!,
                migrated.packDao().getPack("created-partial")!!,
                migrated.packDao().getPack("imported")!!,
            )
        }
        val fullStickers = migrated.stickerDao().getStickersBlocking("created-full")

        assertEquals(0L, fullStickers.first().trimDurationMs)
        assertEquals(2L, full.trayStickerRowId)
        assertEquals(7, full.whatsappSyncedDataVersion)
        assertEquals(7, full.telegramSyncedDataVersion)
        assertNull(partial.telegramSyncedDataVersion)
        assertNull(imported.telegramSyncedDataVersion)
    }

    private fun createVersionSixDatabase() {
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE packs (
                    id TEXT NOT NULL PRIMARY KEY,
                    origin TEXT NOT NULL,
                    telegramSetName TEXT,
                    pushShortName TEXT,
                    sourceUrl TEXT,
                    title TEXT NOT NULL,
                    publisher TEXT NOT NULL,
                    stickerCount INTEGER NOT NULL,
                    isAnimatedPack INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    errorMessage TEXT,
                    warningMessage TEXT,
                    trayIconPath TEXT,
                    isPinned INTEGER NOT NULL,
                    whatsappAdded INTEGER NOT NULL,
                    createdAtMillis INTEGER NOT NULL,
                    updatedAtMillis INTEGER NOT NULL,
                    sourceSignature TEXT,
                    updateAvailable INTEGER NOT NULL,
                    updateCheckEnabled INTEGER NOT NULL,
                    importPartIndex INTEGER NOT NULL,
                    conversionBias TEXT,
                    imageDataVersion INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE stickers (
                    rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    packId TEXT NOT NULL,
                    remoteId TEXT,
                    position INTEGER NOT NULL,
                    emojis TEXT NOT NULL,
                    sniffedContentType TEXT,
                    sourceLocalUri TEXT,
                    isVideo INTEGER NOT NULL,
                    originalFilePath TEXT,
                    convertedWhatsappPath TEXT,
                    convertedTelegramPath TEXT,
                    conversionStatus TEXT NOT NULL,
                    conversionError TEXT,
                    trimStartMs INTEGER NOT NULL,
                    cropLeft REAL,
                    cropTop REAL,
                    cropRight REAL,
                    cropBottom REAL,
                    FOREIGN KEY(packId) REFERENCES packs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_stickers_packId ON stickers(packId)")

            insertPack(db, "created-full", "Created", telegramSetName = "full_by_bot", whatsappAdded = true, tray = true)
            insertPack(db, "created-partial", "Created", telegramSetName = "partial_by_bot")
            insertPack(db, "imported", "Imported", telegramSetName = "source_pack")

            insertSticker(db, 1L, "created-full", position = 1, telegramPath = "/tg/1")
            insertSticker(db, 2L, "created-full", position = 0, telegramPath = "/tg/2")
            insertSticker(db, 3L, "created-partial", position = 0, telegramPath = null)
            insertSticker(db, 4L, "imported", position = 0, telegramPath = "/tg/4")
            db.version = 6
        }
    }

    private fun insertPack(
        db: SQLiteDatabase,
        id: String,
        origin: String,
        telegramSetName: String?,
        whatsappAdded: Boolean = false,
        tray: Boolean = false,
    ) {
        db.insertOrThrow(
            "packs",
            null,
            ContentValues().apply {
                put("id", id)
                put("origin", origin)
                put("telegramSetName", telegramSetName)
                put("title", id)
                put("publisher", "Tester")
                put("stickerCount", if (id == "created-full") 2 else 1)
                put("isAnimatedPack", 0)
                put("status", "Ready")
                if (tray) put("trayIconPath", "/tray.webp")
                put("isPinned", 0)
                put("whatsappAdded", if (whatsappAdded) 1 else 0)
                put("createdAtMillis", 1L)
                put("updatedAtMillis", 1L)
                put("updateAvailable", 0)
                put("updateCheckEnabled", 1)
                put("importPartIndex", 0)
                put("imageDataVersion", 7)
            },
        )
    }

    private fun insertSticker(
        db: SQLiteDatabase,
        rowId: Long,
        packId: String,
        position: Int,
        telegramPath: String?,
    ) {
        db.insertOrThrow(
            "stickers",
            null,
            ContentValues().apply {
                put("rowId", rowId)
                put("packId", packId)
                put("position", position)
                put("emojis", "🙂")
                put("isVideo", 0)
                put("originalFilePath", "/original/$rowId")
                put("convertedWhatsappPath", "/wa/$rowId")
                put("convertedTelegramPath", telegramPath)
                put("conversionStatus", "Done")
                put("trimStartMs", 123L)
            },
        )
    }
}
