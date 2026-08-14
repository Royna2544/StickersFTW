package com.royna.stickersftw.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [PackEntity::class, StickerEntity::class], version = 5, exportSchema = false)
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

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stickers_ftw.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Still the fallback for the one earlier bump that never
                    // got a migration written; 2 -> 3 now takes the path above
                    // instead of dropping everything.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
