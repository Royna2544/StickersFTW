package com.royna.stickersftw.network

import com.royna.stickersftw.BuildConfig
import com.royna.stickersftw.network.telegram.TelegramBotApi
import com.royna.stickersftw.network.telegram.TelegramFileApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Builds a [TelegramStickersApi] against whatever server URL is currently
 * configured. The server URL is a runtime DataStore setting the user can
 * change at any time, not a compile-time constant, so this deliberately
 * builds a fresh (cheap, no I/O) Retrofit instance per call rather than
 * caching one against a URL that might go stale -- only the underlying
 * [OkHttpClient] (connection pool, dispatcher) is a true singleton. */
object RetrofitProvider {
    private val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()
    }

    fun apiFor(serverUrl: String): TelegramStickersApi {
        val normalized = serverUrl.trim().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(sharedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TelegramStickersApi::class.java)
    }

    /** [token] is a live user-editable setting just like [apiFor]'s
     * `serverUrl` -- same rationale for building fresh per call. */
    fun telegramApiFor(token: String): TelegramBotApi = Retrofit.Builder()
        .baseUrl("https://api.telegram.org/bot${token.trim()}/")
        .client(sharedOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TelegramBotApi::class.java)

    fun telegramFileApiFor(token: String): TelegramFileApi = Retrofit.Builder()
        .baseUrl("https://api.telegram.org/file/bot${token.trim()}/")
        .client(sharedOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TelegramFileApi::class.java)
}
