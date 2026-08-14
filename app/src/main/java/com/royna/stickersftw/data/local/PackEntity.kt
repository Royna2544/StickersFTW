package com.royna.stickersftw.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "packs")
data class PackEntity(
    @PrimaryKey val id: String,
    val origin: String,
    /** Canonical, real Telegram set name -- always known for Imported packs
     * (it's what was fetched); null for Created packs until the first
     * successful push, after which it holds the server-returned full
     * "<name>_by_<bot_username>" name. */
    val telegramSetName: String?,
    /** User-chosen short name for a Created pack, before the "_by_<bot>"
     * suffix the server appends. Unused for Imported packs. */
    val pushShortName: String?,
    val sourceUrl: String?,
    val title: String,
    val publisher: String,
    val stickerCount: Int,
    val isAnimatedPack: Boolean,
    val status: String,
    val errorMessage: String?,
    val warningMessage: String?,
    val trayIconPath: String?,
    val isPinned: Boolean,
    val whatsappAdded: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Signature of the *full* upstream Telegram set (title + every sticker's
     * id:emoji) at the time of the last import/update -- null/unused for
     * Created packs. Compared against a fresh fetch to detect drift. */
    val sourceSignature: String? = null,
    val updateAvailable: Boolean = false,
    val updateCheckEnabled: Boolean = true,
    /** Part index used at import time; replayed verbatim when the user
     * chooses to update (always 0 for a custom or single-part import). */
    val importPartIndex: Int = 0,
    /** Which ConversionBias produced this pack's animated stickers, so the
     * pack can say what it was made with. Null for a static pack (the knob
     * changes nothing there) and for anything converted before the setting
     * existed. */
    val conversionBias: String? = null,
    /** What the provider reports as WhatsApp's `image_data_version`.
     *
     * WhatsApp caches a pack's assets against this value and only re-reads
     * them when it changes, so every change to the *converted* content has to
     * increment it. Left alone, adding or replacing a sticker succeeds
     * everywhere in this app and changes nothing in WhatsApp, which looks like
     * the write failed rather than the cache holding.
     *
     * Monotonic on purpose: a re-import of a pack WhatsApp has already seen
     * carries the old value forward and bumps it, rather than restarting at 1
     * and relying on "different" being enough. */
    val imageDataVersion: Int = 1,
    /** Sticker row whose source should be used when the tray is rebuilt.
     * Null preserves a previously rendered tray that is no longer linked. */
    val trayStickerRowId: Long? = null,
    /** Local content revision last handed to an installed WhatsApp pack. */
    val whatsappSyncedDataVersion: Int? = null,
    /** Local content revision represented by the linked Telegram set. */
    val telegramSyncedDataVersion: Int? = null,
)
