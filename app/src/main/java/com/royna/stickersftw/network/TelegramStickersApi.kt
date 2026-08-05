package com.royna.stickersftw.network

import com.royna.stickersftw.network.dto.BotInfoDto
import com.royna.stickersftw.network.dto.StickerSetDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/** Client for the StickersTW_BotServer /v1 API. Return types are all
 * [Response] (never bare suspend-return) so 400/404/429/500 are inspectable
 * via [Response.code] instead of throwing -- the server never uses 2xx to
 * mean failure and never omits a JSON error body on the v1 routes. */
interface TelegramStickersApi {
    @GET("v1/set/{name}")
    suspend fun getSet(@Path("name") name: String): Response<StickerSetDto>

    @Streaming
    @GET("v1/set/{name}/{id}")
    suspend fun getSticker(
        @Path("name") name: String,
        @Path("id") id: String,
    ): Response<ResponseBody>

    @Streaming
    @GET("v1/set/{name}/{id}/thumbnail")
    suspend fun getThumbnail(
        @Path("name") name: String,
        @Path("id") id: String,
    ): Response<ResponseBody>

    @GET("v1/bot")
    suspend fun getBotInfo(): Response<BotInfoDto>

    @Multipart
    @POST("v1/set/{name}")
    suspend fun pushSticker(
        @Path("name") shortName: String,
        @Part("user_id") userId: RequestBody,
        @Part("title") title: RequestBody?,
        @Part("format") format: RequestBody,
        @Part("emojis") emojis: RequestBody,
        @Part sticker: MultipartBody.Part,
    ): Response<StickerSetDto>
}
