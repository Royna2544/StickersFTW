package com.royna.stickersftw.network.telegram

import com.royna.stickersftw.network.telegram.dto.TgChat
import com.royna.stickersftw.network.telegram.dto.TgEnvelope
import com.royna.stickersftw.network.telegram.dto.TgFile
import com.royna.stickersftw.network.telegram.dto.TgStickerSet
import com.royna.stickersftw.network.telegram.dto.TgUser
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/** Direct client for Telegram's own Bot API
 * (https://core.telegram.org/bots/api), used when the user supplies their
 * own bot token instead of a companion-server URL. Base URL is
 * `https://api.telegram.org/bot<token>/`, built per-token in
 * [com.royna.stickersftw.network.RetrofitProvider.telegramApiFor]. */
interface TelegramBotApi {
    @GET("getMe")
    suspend fun getMe(): Response<TgEnvelope<TgUser>>

    @GET("getStickerSet")
    suspend fun getStickerSet(@Query("name") name: String): Response<TgEnvelope<TgStickerSet>>

    @GET("getFile")
    suspend fun getFile(@Query("file_id") fileId: String): Response<TgEnvelope<TgFile>>

    @GET("getChat")
    suspend fun getChat(@Query("chat_id") chatId: String): Response<TgEnvelope<TgChat>>

    @Multipart
    @POST("createNewStickerSet")
    suspend fun createNewStickerSet(
        @Part("user_id") userId: RequestBody,
        @Part("name") name: RequestBody,
        @Part("title") title: RequestBody,
        @Part("stickers") stickers: RequestBody,
        @Part("sticker_format") stickerFormat: RequestBody,
        @Part sticker: MultipartBody.Part,
    ): Response<TgEnvelope<Boolean>>

    @Multipart
    @POST("addStickerToSet")
    suspend fun addStickerToSet(
        @Part("user_id") userId: RequestBody,
        @Part("name") name: RequestBody,
        @Part("sticker") sticker: RequestBody,
        @Part stickerFile: MultipartBody.Part,
    ): Response<TgEnvelope<Boolean>>

    @FormUrlEncoded
    @POST("deleteStickerSet")
    suspend fun deleteStickerSet(@Field("name") name: String): Response<TgEnvelope<Boolean>>
}
