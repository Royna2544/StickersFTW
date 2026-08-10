package com.royna.stickersftw.model

import com.royna.stickersftw.network.TelegramBackendConfig

enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** How the app talks to Telegram: through a self-hosted companion server,
 * or directly against `api.telegram.org` using a user-supplied bot token.
 * See [AppSettings.backendConfig]. */
enum class BackendMode {
    ServerUrl,
    BotToken,
}

data class AppSettings(
    val serverUrl: String = "http://10.0.2.2:8080",
    val themeMode: ThemeMode = ThemeMode.System,
    val telegramUserId: String = "",
    val updateChecksEnabled: Boolean = true,
    val pingTestsEnabled: Boolean = true,
    val backendMode: BackendMode = BackendMode.ServerUrl,
    /** Stored encrypted at rest -- see `data.SecureTokenStore`, not plain
     * DataStore like the other fields here. */
    val botToken: String = "",
)

/** The [TelegramBackendConfig] implied by the current backend-mode
 * settings -- used at every call site that used to read `serverUrl`
 * directly. */
val AppSettings.backendConfig: TelegramBackendConfig
    get() = when (backendMode) {
        BackendMode.ServerUrl -> TelegramBackendConfig.ServerUrl(serverUrl)
        BackendMode.BotToken -> TelegramBackendConfig.BotToken(botToken)
    }

/** Live result of actually reaching the configured server -- distinct from
 * [AppSettings.serverUrl] itself, which says nothing about reachability. */
sealed class ServerConnectionStatus {
    /** Ping tests are off, or none has run yet this session. */
    data object Unknown : ServerConnectionStatus()
    data object Checking : ServerConnectionStatus()
    data object Connected : ServerConnectionStatus()
    data object Failed : ServerConnectionStatus()
}

enum class TelegramClientKind {
    /** The real, official Telegram app. */
    Official,
    /** Telegram X -- an alternative client also published by Telegram itself. */
    OfficialAlt,
    /** A community fork/mod built on Telegram's open-source client. */
    ThirdParty,
}

data class TelegramClientInfo(
    val kind: TelegramClientKind,
    val displayName: String,
    val packageName: String,
)

data class InstalledAppsState(
    val telegramClient: TelegramClientInfo? = null,
    val whatsappInstalled: Boolean = false,
    val whatsappBusinessInstalled: Boolean = false,
) {
    val telegramInstalled: Boolean get() = telegramClient != null
}

/** Where a pack came from: fetched from an existing Telegram set, or built
 * locally from device media. This determines which publish actions make
 * sense -- an Imported pack can only be added to WhatsApp; a Created pack
 * can be pushed to Telegram and/or added to WhatsApp. */
enum class PackOrigin {
    Imported,
    Created,
}

enum class PackStatus {
    Building,
    Downloading,
    Converting,
    Ready,
    Failed,
}

sealed class TelegramPushState {
    data object NotPushed : TelegramPushState()
    data class Pushed(val fullSetName: String) : TelegramPushState()
    /** The Telegram set exists (some stickers made it) but fewer stickers
     * are on Telegram than are in the local pack -- a prior push attempt
     * partially failed. The retry/push button should stay available. */
    data class Partial(val fullSetName: String, val pushedCount: Int, val totalCount: Int) : TelegramPushState()
}

data class StickerPack(
    val id: String,
    val title: String,
    val author: String,
    val origin: PackOrigin,
    val stickerCount: Int,
    val isAnimated: Boolean,
    val isPinned: Boolean = false,
    val updatedLabel: String = "Today",
    val sourceUrl: String? = null,
    val status: PackStatus = PackStatus.Building,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val trayIconPath: String? = null,
    val previewStickerPaths: List<String> = emptyList(),
    val previewEmojis: List<String> = emptyList(),
    val whatsappAdded: Boolean? = null,
    val telegramPushState: TelegramPushState = TelegramPushState.NotPushed,
    val updateAvailable: Boolean = false,
    /** The canonical Telegram set name this pack was imported from, and
     * which slice of it (-1 = hand-picked custom selection, otherwise the
     * 0-based part index) -- null/0 for a Created pack. Lets a new import
     * be recognized as "the same pack/part already imported" instead of
     * silently creating a duplicate entry. */
    val telegramSetName: String? = null,
    val importPartIndex: Int = 0,
)

/** One entry in the read-only full sticker grid viewer (see
 * StickerGridScreen) -- unlike [StickerPack.previewStickerPaths]/
 * [previewEmojis], this pairs each sticker with its own emoji and isn't
 * truncated to a small preview count. */
data class StickerGridItem(
    val path: String,
    val emoji: String,
)

/** Shared progress shape for both the fetch-and-convert flow
 * (ConversionScreen) and the create-and-publish flow (CreatePackScreen). */
data class ConversionUiState(
    val packId: String? = null,
    val progress: Float = 0f,
    val stage: String = "Waiting",
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    /** When the operation started, for the elapsed-time readout. Held here
     * rather than in the screen so it survives navigating away and back, and
     * so a "run in background" operation still reports its real total. */
    val startedAtMillis: Long = 0L,
    /** The pack contains video stickers, which take minutes rather than
     * seconds. Sticky once seen. */
    val isSlowFormat: Boolean = false,
)

enum class PickedMediaKind {
    Image,
    Video,
}

data class PickedMediaItem(
    val uri: String,
    val kind: PickedMediaKind,
    val emoji: String = "🙂",
)
