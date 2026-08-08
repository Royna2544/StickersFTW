package com.royna.stickersftw.network

import com.royna.stickersftw.network.dto.BotInfoDto
import com.royna.stickersftw.network.dto.StickerSetDto
import com.royna.stickersftw.network.dto.UserVerifyDto
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** [TelegramBackend] backed by the companion server (StickersTW_BotServer)
 * -- today's only backend, unchanged in behavior; just retargeted from
 * direct [TelegramStickersApi] calls in [com.royna.stickersftw.data.StickerPackRepository]
 * to this shared implementation. */
class ServerBackend(
    private val serverUrl: String,
    private val api: TelegramStickersApi,
) : TelegramBackend {

    override suspend fun getSet(name: String, force: Boolean): ApiResult<StickerSetDto> =
        withRateLimitRetry { api.getSet(name, force) }.toApiResult()

    override suspend fun downloadSticker(
        name: String,
        id: String,
        output: File,
        contentTypeHint: String?,
    ): String? {
        val response = withRateLimitRetry { api.getSticker(name, id) }
        val body = if (response.isSuccessful) response.body() else null
        return if (body == null) {
            null
        } else {
            output.parentFile?.mkdirs()
            body.byteStream().use { input -> output.outputStream().use { out -> input.copyTo(out) } }
            response.headers()["Content-Type"]
        }
    }

    override suspend fun getBotInfo(): ApiResult<BotInfoDto> =
        withRateLimitRetry { api.getBotInfo() }.toApiResult()

    override suspend fun verifyUserStartedChat(userId: String): ApiResult<UserVerifyDto> =
        withRateLimitRetry { api.verifyUserStartedChat(userId) }.toApiResult()

    override suspend fun pushSticker(
        shortName: String,
        userId: String,
        title: String?,
        format: String,
        emojis: List<String>,
        file: File,
    ): ApiResult<StickerSetDto> {
        val mediaType = if (format == "video") "video/webm".toMediaType() else "image/webp".toMediaType()
        val stickerPart = MultipartBody.Part.createFormData("sticker", file.name, file.asRequestBody(mediaType))
        val plainText = "text/plain".toMediaType()
        return withRateLimitRetry {
            api.pushSticker(
                shortName = shortName,
                userId = userId.toRequestBody(plainText),
                title = title?.toRequestBody(plainText),
                format = format.toRequestBody(plainText),
                emojis = emojis.joinToString(",").toRequestBody(plainText),
                sticker = stickerPart,
            )
        }.toApiResult()
    }

    override suspend fun deleteStickerSet(name: String): ApiResult<Unit> =
        withRateLimitRetry { api.deleteStickerSet(name) }.toApiUnitResult()

    override suspend fun thumbnailUrl(setName: String, id: String, thumbFileId: String?): String? =
        "$serverUrl/v1/set/$setName/$id/thumbnail"

    override suspend fun ping(): Boolean = try {
        api.getBotInfo().isSuccessful
    } catch (_: Exception) {
        false
    }
}
