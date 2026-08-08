package com.royna.stickersftw.network.telegram

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/** Streams raw file bytes from Telegram's file host. Base URL is
 * `https://api.telegram.org/file/bot<token>/`, built per-token in
 * [com.royna.stickersftw.network.RetrofitProvider.telegramFileApiFor] --
 * [path] is the `file_path` resolved via [TelegramBotApi.getFile]. */
interface TelegramFileApi {
    @Streaming
    @GET("{path}")
    suspend fun downloadFile(@Path("path", encoded = true) path: String): Response<ResponseBody>
}
