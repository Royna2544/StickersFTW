package com.royna.stickersftw.network

/** Resolves a [TelegramBackendConfig] into a callable [TelegramBackend].
 * Called once per repository operation, same as `RetrofitProvider.apiFor`
 * was called before this abstraction existed -- not cached across calls. */
object TelegramBackendProvider {
    fun resolve(config: TelegramBackendConfig): TelegramBackend = when (config) {
        is TelegramBackendConfig.ServerUrl -> ServerBackend(
            serverUrl = config.url.trim().trimEnd('/'),
            api = RetrofitProvider.apiFor(config.url),
        )
        is TelegramBackendConfig.BotToken -> DirectTelegramBackend(
            token = config.token.trim(),
            api = RetrofitProvider.telegramApiFor(config.token),
            fileApi = RetrofitProvider.telegramFileApiFor(config.token),
        )
    }
}
