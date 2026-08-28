package com.royna.stickersftw.network.telegram.dto

import com.google.gson.annotations.SerializedName

/** Telegram Bot API's uniform response envelope
 * (https://core.telegram.org/bots/api#making-requests). On failure, the
 * `error_code`/`description` fields alone already match
 * [com.royna.stickersftw.network.dto.ErrorBodyDto]'s shape, so
 * [com.royna.stickersftw.network.toApiErrorOrNull] parses Telegram's error
 * bodies unmodified. */
data class TgEnvelope<T>(
    val ok: Boolean,
    val result: T? = null,
    @SerializedName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

data class TgUser(
    val id: Long,
    val username: String? = null,
)

data class TgStickerSet(
    val name: String,
    val title: String,
    val stickers: List<TgSticker> = emptyList(),
)

data class TgSticker(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_unique_id") val fileUniqueId: String? = null,
    val width: Int,
    val height: Int,
    @SerializedName("is_animated") val isAnimated: Boolean = false,
    @SerializedName("is_video") val isVideo: Boolean = false,
    val thumbnail: TgPhotoSize? = null,
    val emoji: String? = null,
    @SerializedName("file_size") val fileSize: Int? = null,
)

data class TgPhotoSize(
    @SerializedName("file_id") val fileId: String,
    val width: Int,
    val height: Int,
    @SerializedName("file_size") val fileSize: Int? = null,
)

data class TgFile(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_size") val fileSize: Int? = null,
    @SerializedName("file_path") val filePath: String? = null,
)

data class TgChat(
    val id: Long,
)

/** JSON shape of a single element of `createNewStickerSet`/`addStickerToSet`'s
 * `stickers` (or `sticker`) parameter -- referencing the multipart-attached
 * file via Telegram's `attach://<name>` convention. */
data class TgInputSticker(
    val sticker: String,
    val format: String,
    @SerializedName("emoji_list") val emojiList: List<String>,
)
