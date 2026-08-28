package com.royna.stickersftw.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stickers",
    foreignKeys = [
        ForeignKey(
            entity = PackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("packId")],
)
data class StickerEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val packId: String,
    /** Telegram download locator (`file_id`) for imported stickers. */
    val remoteId: String?,
    /** Telegram's stable `file_unique_id`. Kept separately because `file_id`
     * can rotate and is still required for downloading the current object. */
    val remoteStableId: String? = null,
    val position: Int,
    /** Comma-joined, 1-3 emoji -- matches both WhatsApp's and Telegram's
     * per-sticker emoji column shape directly. */
    val emojis: String,
    val sniffedContentType: String?,
    /** Original content:// URI, for a sticker picked from the device in the
     * Create Pack flow. */
    val sourceLocalUri: String?,
    val isVideo: Boolean,
    val originalFilePath: String?,
    val convertedWhatsappPath: String?,
    val convertedTelegramPath: String?,
    val conversionStatus: String,
    val conversionError: String?,
    /** Where in the source clip this sticker starts, in milliseconds. Only
     * ever non-zero for a locally picked video the user trimmed. */
    val trimStartMs: Long = 0L,
    /** Exact selected clip length in milliseconds. Zero is the legacy value
     * and means to use the destination's maximum duration. */
    val trimDurationMs: Long = 0L,
    /** Source-relative non-destructive crop. All four values are null when
     * the whole image/frame should be kept. */
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropRight: Float? = null,
    val cropBottom: Float? = null,
)
