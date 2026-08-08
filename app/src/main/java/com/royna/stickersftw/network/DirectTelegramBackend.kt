package com.royna.stickersftw.network

import com.google.gson.Gson
import com.royna.stickersftw.network.dto.BotInfoDto
import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import com.royna.stickersftw.network.dto.UserVerifyDto
import com.royna.stickersftw.network.telegram.TelegramBotApi
import com.royna.stickersftw.network.telegram.TelegramFileApi
import com.royna.stickersftw.network.telegram.dto.TgEnvelope
import com.royna.stickersftw.network.telegram.dto.TgInputSticker
import com.royna.stickersftw.network.telegram.dto.TgStickerSet
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

/** [TelegramBackend] that calls `api.telegram.org` directly with a
 * user-supplied bot token -- no companion server involved. See the DTOs in
 * `network/telegram/dto/` and [TelegramBotApi]/[TelegramFileApi] for the
 * raw shapes this maps to/from. */
class DirectTelegramBackend(
    private val token: String,
    private val api: TelegramBotApi,
    private val fileApi: TelegramFileApi,
) : TelegramBackend {
    /** Resolved lazily via [getBotInfo] and cached on this instance --
     * [com.royna.stickersftw.data.StickerPackRepository] resolves one
     * backend per operation (mirroring the old per-call `RetrofitProvider.apiFor`),
     * so this naturally caches for the lifetime of one push/delete loop. */
    private var cachedUsername: String? = null

    override suspend fun getSet(name: String, force: Boolean): ApiResult<StickerSetDto> {
        val result = withRateLimitRetry { api.getStickerSet(name) }.toEnvelopeResult()
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(result.value.toStickerSetDto())
        }
    }

    private fun TgStickerSet.toStickerSetDto() = StickerSetDto(
        name = name,
        title = title,
        stickers = stickers.map { sticker ->
            StickerDto(
                id = sticker.fileId,
                width = sticker.width,
                height = sticker.height,
                size = sticker.fileSize ?: 0,
                thumb = sticker.thumbnail?.fileId,
                emoji = sticker.emoji,
                knownContentType = when {
                    sticker.isVideo -> "video/webm"
                    sticker.isAnimated -> "application/x-tgsticker"
                    else -> "image/webp"
                },
            )
        },
    )

    override suspend fun downloadSticker(
        name: String,
        id: String,
        output: File,
        contentTypeHint: String?,
    ): String? {
        val fileResult = withRateLimitRetry { api.getFile(id) }.toEnvelopeResult()
        val filePath = (fileResult as? ApiResult.Success)?.value?.filePath ?: return null
        val response = withRateLimitRetry { fileApi.downloadFile(filePath) }
        val body = if (response.isSuccessful) response.body() else null
        return if (body == null) {
            null
        } else {
            output.parentFile?.mkdirs()
            body.byteStream().use { input -> output.outputStream().use { out -> input.copyTo(out) } }
            contentTypeHint ?: response.headers()["Content-Type"]
        }
    }

    override suspend fun getBotInfo(): ApiResult<BotInfoDto> {
        val result = withRateLimitRetry { api.getMe() }.toEnvelopeResult()
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val username = result.value.username
                    ?: return ApiResult.Failure(ApiError.Unknown(0, "This bot has no username."))
                cachedUsername = username
                ApiResult.Success(BotInfoDto(username))
            }
        }
    }

    private suspend fun resolveUsername(): String? =
        cachedUsername ?: (getBotInfo() as? ApiResult.Success)?.value?.username

    override suspend fun verifyUserStartedChat(userId: String): ApiResult<UserVerifyDto> {
        val response = withRateLimitRetry { api.getChat(userId) }
        return when {
            response.isSuccessful -> ApiResult.Success(UserVerifyDto(started = true))
            response.code() == 400 -> ApiResult.Success(UserVerifyDto(started = false))
            else -> ApiResult.Failure(response.toApiErrorOrNull() ?: ApiError.Unknown(response.code(), null))
        }
    }

    override suspend fun pushSticker(
        shortName: String,
        userId: String,
        title: String?,
        format: String,
        emojis: List<String>,
        file: File,
    ): ApiResult<StickerSetDto> {
        val username = resolveUsername()
            ?: return ApiResult.Failure(ApiError.Unknown(0, "Could not resolve the bot's username."))
        val fullName = "${shortName}_by_$username"
        val mediaType = if (format == "video") "video/webm".toMediaType() else "image/webp".toMediaType()
        val stickerPart = MultipartBody.Part.createFormData("stk", file.name, file.asRequestBody(mediaType))
        val plainText = "text/plain".toMediaType()
        val emojiList = emojis.ifEmpty { listOf("🙂") }

        val result: ApiResult<Boolean> = if (title != null) {
            val stickersJson = gson.toJson(listOf(TgInputSticker("attach://stk", format, emojiList)))
            withRateLimitRetry {
                api.createNewStickerSet(
                    userId = userId.toRequestBody(plainText),
                    name = fullName.toRequestBody(plainText),
                    title = title.toRequestBody(plainText),
                    stickers = stickersJson.toRequestBody(plainText),
                    stickerFormat = format.toRequestBody(plainText),
                    sticker = stickerPart,
                )
            }.toEnvelopeResult()
        } else {
            val stickerJson = gson.toJson(TgInputSticker("attach://stk", format, emojiList))
            withRateLimitRetry {
                api.addStickerToSet(
                    userId = userId.toRequestBody(plainText),
                    name = fullName.toRequestBody(plainText),
                    sticker = stickerJson.toRequestBody(plainText),
                    stickerFile = stickerPart,
                )
            }.toEnvelopeResult()
        }

        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(StickerSetDto(name = fullName, title = title ?: "", stickers = emptyList()))
        }
    }

    override suspend fun deleteStickerSet(name: String): ApiResult<Unit> {
        val result = withRateLimitRetry { api.deleteStickerSet(name) }.toEnvelopeResult()
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }
    }

    override suspend fun thumbnailUrl(setName: String, id: String, thumbFileId: String?): String? {
        val fileResult = withRateLimitRetry { api.getFile(thumbFileId ?: id) }.toEnvelopeResult()
        val filePath = (fileResult as? ApiResult.Success)?.value?.filePath ?: return null
        return "https://api.telegram.org/file/bot$token/$filePath"
    }

    override suspend fun ping(): Boolean = try {
        api.getMe().isSuccessful
    } catch (_: Exception) {
        false
    }

    private companion object {
        val gson = Gson()
    }
}

/** Unwraps Telegram's `{ok, result, error_code, description}` envelope into
 * an [ApiResult], reusing [toApiErrorOrNull] for the HTTP-level failure
 * mapping (its shape matches Telegram's error fields exactly) and treating
 * an `ok: false` or missing `result` on an otherwise-2xx response as
 * [ApiError.Unknown] defensively. */
private fun <T> Response<TgEnvelope<T>>.toEnvelopeResult(): ApiResult<T> {
    toApiErrorOrNull()?.let { return ApiResult.Failure(it) }
    val envelope = body()
    val result = envelope?.result
    return if (envelope == null || !envelope.ok || result == null) {
        ApiResult.Failure(ApiError.Unknown(code(), envelope?.description))
    } else {
        ApiResult.Success(result)
    }
}
