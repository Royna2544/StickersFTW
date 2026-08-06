package com.royna.stickersftw.model

enum class ThemeMode {
    System,
    Light,
    Dark,
}

data class AppSettings(
    val serverUrl: String = "http://10.0.2.2:8080",
    val themeMode: ThemeMode = ThemeMode.System,
    val telegramUserId: String = "",
    val updateChecksEnabled: Boolean = true,
)

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
