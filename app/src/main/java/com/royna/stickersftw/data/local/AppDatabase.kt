package com.royna.stickersftw.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [PackEntity::class, StickerEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packDao(): PackDao
    abstract fun stickerDao(): StickerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Adds the column recording which ConversionBias built a pack.
         *
         * Written as a real migration rather than another destructive bump:
         * by this version people have libraries of imported packs, and each
         * one is minutes of conversion to rebuild. Nullable with no default,
         * because packs converted before the setting existed genuinely have
         * no answer and should show no tag rather than a made-up one. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE packs ADD COLUMN conversionBias TEXT")
            }
        }

        /** Adds the per-pack image_data_version WhatsApp caches against.
         *
         * Defaults to 1, which is exactly what the provider used to report for
         * every pack, so packs WhatsApp has already cached keep the value it
         * saw and only move once their content next changes. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE packs ADD COLUMN imageDataVersion INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /** Adds where a trimmed clip starts. Zero for everything that
         * exists already, which is what those stickers were converted with. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE stickers ADD COLUMN trimStartMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Adds a normalized crop rectangle for locally picked media. Null is
         * the old behaviour: preserve the whole source and pad to square. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE stickers ADD COLUMN cropLeft REAL")
                connection.execSQL("ALTER TABLE stickers ADD COLUMN cropTop REAL")
                connection.execSQL("ALTER TABLE stickers ADD COLUMN cropRight REAL")
                connection.execSQL("ALTER TABLE stickers ADD COLUMN cropBottom REAL")
            }
        }

        /** Adds the exact selected video length and the revision anchors used
         * by pack editing. Existing video rows retain the old destination
         * maximum because zero is the duration's legacy sentinel. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE stickers ADD COLUMN trimDurationMs INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL("ALTER TABLE packs ADD COLUMN trayStickerRowId INTEGER")
                connection.execSQL("ALTER TABLE packs ADD COLUMN whatsappSyncedDataVersion INTEGER")
                connection.execSQL("ALTER TABLE packs ADD COLUMN telegramSyncedDataVersion INTEGER")
                connection.execSQL(
                    """
                    UPDATE packs
                    SET trayStickerRowId = (
                        SELECT stickers.rowId
                        FROM stickers
                        WHERE stickers.packId = packs.id
                          AND stickers.convertedWhatsappPath IS NOT NULL
                        ORDER BY stickers.position, stickers.rowId
                        LIMIT 1
                    )
                    WHERE packs.trayIconPath IS NOT NULL
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    UPDATE packs
                    SET whatsappSyncedDataVersion = imageDataVersion
                    WHERE whatsappAdded = 1
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    UPDATE packs
                    SET telegramSyncedDataVersion = imageDataVersion
                    WHERE origin = 'Created'
                      AND telegramSetName IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM stickers
                          WHERE stickers.packId = packs.id
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM stickers
                          WHERE stickers.packId = packs.id
                            AND stickers.convertedTelegramPath IS NULL
                      )
                    """.trimIndent(),
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stickers_ftw.db",
                )
                    .addMigrations(
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                    )
                    // Still the fallback for the one earlier bump that never
                    // got a migration written; 2 -> 3 now takes the path above
                    // instead of dropping everything.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
