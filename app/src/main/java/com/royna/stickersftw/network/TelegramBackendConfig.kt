package com.royna.stickersftw.network

/** Which Telegram backend to talk to, and the credential/address it needs.
 * Threaded through [com.royna.stickersftw.data.StickerPackRepository] in
 * place of the old bare `serverUrl: String` parameter, and resolved to a
 * [TelegramBackend] via [TelegramBackendProvider]. */
sealed class TelegramBackendConfig {
    data class ServerUrl(val url: String) : TelegramBackendConfig()
    data class BotToken(val token: String) : TelegramBackendConfig()
}
