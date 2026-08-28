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
    val conversionBias: ConversionBias = ConversionBias.Auto,
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

/** Whether the content WhatsApp most recently accepted still matches the
 * local pack revision. Detection that a pack is installed is deliberately
 * separate from acknowledging that an Add-to-WhatsApp flow completed. */
enum class WhatsappFreshnessState {
    NotAdded,
    Current,
    NeedsRefresh,
}

/** Relationship between a Created pack and its remote Telegram set. A
 * partially pushed pack is retryable only while its local revision has not
 * changed; editing it makes the remote subset [OutOfDate]. */
enum class TelegramFreshnessState {
    NotPushed,
    Partial,
    Current,
    OutOfDate,
}

internal fun deriveWhatsappFreshness(
    whatsappAdded: Boolean,
    imageDataVersion: Int,
    syncedDataVersion: Int?,
): WhatsappFreshnessState = when {
    !whatsappAdded -> WhatsappFreshnessState.NotAdded
    syncedDataVersion == imageDataVersion -> WhatsappFreshnessState.Current
    else -> WhatsappFreshnessState.NeedsRefresh
}

/** Only complete, ready Telegram imports can be safely rebuilt as a unit.
 * Unknown historical builds are treated as old, while a pack made by a
 * newer build is left alone when the app itself has been downgraded. */
internal fun deriveNeedsReconversion(
    origin: PackOrigin,
    status: PackStatus,
    convertedAppVersionCode: Int?,
    currentAppVersionCode: Int,
): Boolean = origin == PackOrigin.Imported &&
    status == PackStatus.Ready &&
    (convertedAppVersionCode == null || convertedAppVersionCode < currentAppVersionCode)

internal fun deriveTelegramFreshness(
    origin: PackOrigin,
    imageDataVersion: Int,
    syncedDataVersion: Int?,
    hasTelegramSet: Boolean,
    pushedStickerCount: Int,
    totalStickerCount: Int,
): TelegramFreshnessState {
    if (origin != PackOrigin.Created || !hasTelegramSet) {
        return TelegramFreshnessState.NotPushed
    }
    // A partial set also remembers which local revision it represents. Once
    // that revision changes, retrying would append mismatched content.
    if (syncedDataVersion != null && syncedDataVersion != imageDataVersion) {
        return TelegramFreshnessState.OutOfDate
    }
    return if (
        hasTelegramSet &&
        totalStickerCount > 0 &&
        pushedStickerCount == totalStickerCount &&
        syncedDataVersion == imageDataVersion
    ) {
        TelegramFreshnessState.Current
    } else {
        TelegramFreshnessState.Partial
    }
}

data class StickerPack(
    val id: String,
    val title: String,
    val author: String,
    val origin: PackOrigin,
    val stickerCount: Int,
    val isAnimated: Boolean,
    /** Monotonic local content revision captured when launching an external
     * target flow, so its result cannot acknowledge a later edit. */
    val imageDataVersion: Int = 1,
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
    val whatsappFreshness: WhatsappFreshnessState = WhatsappFreshnessState.NotAdded,
    val telegramFreshness: TelegramFreshnessState = TelegramFreshnessState.NotPushed,
    val updateAvailable: Boolean = false,
    /** The canonical Telegram set name and selection used by this import.
     * Negative [importPartIndex] values identify custom/type-split subsets;
     * [sourcePartIndex] retains their original 0-based part so a new import
     * is still recognized as the same source pack/part. */
    val telegramSetName: String? = null,
    val importPartIndex: Int = 0,
    val sourcePartIndex: Int? = null,
    /** Which [ConversionBias] this pack's animated stickers were built with.
     * Null for static packs and for anything converted before the setting
     * existed. */
    val conversionBias: ConversionBias? = null,
    /** App build that last completed a full conversion of this imported pack.
     * The version code drives freshness; the name is presentation-only. */
    val convertedAppVersionCode: Int? = null,
    val convertedAppVersionName: String? = null,
    val needsReconversion: Boolean = false,
    /** Imported or otherwise upstream-linked packs are preserved by cloning
     * them to a local Created pack before the first editor mutation. */
    val requiresLocalRemix: Boolean = false,
)

/** One stable, editable entry in a pack's full sticker grid. File paths stay
 * presentation-only; mutations address [rowId] so a versioned output swap
 * cannot make an action target the wrong sticker. */
data class StickerGridItem(
    val rowId: Long,
    val position: Int,
    val path: String,
    val emoji: String,
    val isVideo: Boolean,
    val isTray: Boolean,
    /** TGS/Lottie sources cannot use the bitmap crop/range editor. */
    val canEditVisual: Boolean,
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
    /** Set when a mixed pack was split: the animated half, which is a
     * separate pack the user still has to add to WhatsApp. */
    val splitPackId: String? = null,
)

enum class PickedMediaKind {
    Image,
    Video,
}

data class PickedMediaItem(
    val uri: String,
    val kind: PickedMediaKind,
    val emoji: String = "🙂",
    /** Where in a clip the sticker should start, for a video longer than a
     * sticker is allowed to be. Zero for images and for clips that already
     * fit. */
    val trimStartMs: Long = 0L,
    /** Exact length of the selected clip. Zero preserves the legacy behavior:
     * each destination uses its own maximum duration from [trimStartMs]. */
    val trimDurationMs: Long = 0L,
    /** Null keeps the entire source and pads its shorter side. Non-null crops
     * before the normal sticker sizing step. */
    val crop: MediaCrop? = null,
)
