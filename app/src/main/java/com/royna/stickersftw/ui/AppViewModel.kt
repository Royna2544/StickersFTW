package com.royna.stickersftw.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.royna.stickersftw.data.SettingsRepository
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.data.model.PackOperationProgress
import com.royna.stickersftw.data.model.PreviewResult
import com.royna.stickersftw.data.model.PreviewSticker
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramClientInfo
import com.royna.stickersftw.model.TelegramClientKind
import com.royna.stickersftw.model.ThemeMode
import com.royna.stickersftw.network.RetrofitProvider
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ImportPreviewUiState {
    data object Idle : ImportPreviewUiState()
    data object Loading : ImportPreviewUiState()
    data class Loaded(
        val shortName: String,
        val title: String,
        val totalStickerCount: Int,
        val partCount: Int,
        val stickers: List<PreviewSticker>,
        val emojis: List<String>,
        val warning: String?,
    ) : ImportPreviewUiState()
    data class Error(val message: String) : ImportPreviewUiState()
}

/** Official and official-ish Telegram clients, checked by exact package
 * name in this order. Everything else (community forks/mods) is discovered
 * dynamically -- see [AppViewModel.detectThirdPartyTelegramClient] -- rather
 * than hardcoded, since there are many and new ones appear regularly. */
private val OFFICIAL_TELEGRAM_CANDIDATES = listOf(
    TelegramClientInfo(TelegramClientKind.Official, "Telegram", "org.telegram.messenger"),
    TelegramClientInfo(TelegramClientKind.Official, "Telegram", "org.telegram.messenger.web"),
    TelegramClientInfo(TelegramClientKind.OfficialAlt, "Telegram X", "org.thunderdog.challegram"),
)

/** Telegram's own custom URI scheme for deep links (t.me links resolve
 * through it). Any app that registers to handle it is, by construction, a
 * Telegram-family client -- this is what lets third-party forks/mods be
 * recognized by their own real package name and app label instead of
 * maintaining a hardcoded list of every fork that exists. */
private const val TELEGRAM_URI_SCHEME = "tg"

class AppViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val packRepository = StickerPackRepository(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    val packs: StateFlow<List<StickerPack>> = packRepository.observePacks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _installedApps = MutableStateFlow(InstalledAppsState())
    val installedApps: StateFlow<InstalledAppsState> = _installedApps.asStateFlow()

    private val _conversion = MutableStateFlow(ConversionUiState())
    val conversion: StateFlow<ConversionUiState> = _conversion.asStateFlow()

    private val _importPreview = MutableStateFlow<ImportPreviewUiState>(ImportPreviewUiState.Idle)
    val importPreview: StateFlow<ImportPreviewUiState> = _importPreview.asStateFlow()

    private val _botUsername = MutableStateFlow<String?>(null)
    val botUsername: StateFlow<String?> = _botUsername.asStateFlow()

    private val _isRefreshingPacks = MutableStateFlow(false)
    val isRefreshingPacks: StateFlow<Boolean> = _isRefreshingPacks.asStateFlow()

    /** Null means "no custom selection in progress" (the auto-split parts
     * are used instead). Set by [beginCustomSelection] once the user taps
     * "I want to pick my own", and cleared on every fresh [loadPreview]
     * call since the pack's sticker order/contents could have changed
     * server-side, which would make old selected ids stale or wrong. */
    private val _customSelection = MutableStateFlow<Set<String>?>(null)
    val customSelection: StateFlow<Set<String>?> = _customSelection.asStateFlow()

    /** The Telegram pack link/short-name last submitted to [loadPreview] --
     * exposed so the Import screen can re-populate its text field when
     * returning to it (e.g. via "Convert other parts of pack") instead of
     * showing a blank field under a still-loaded preview. */
    var lastPreviewInput: String = ""
        private set

    private var operationJob: Job? = null

    init {
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        _installedApps.value = InstalledAppsState(
            telegramClient = detectTelegramClient(),
            whatsappInstalled = isAnyPackageInstalled("com.whatsapp"),
            whatsappBusinessInstalled = isAnyPackageInstalled("com.whatsapp.w4b"),
        )
    }

    /** Real official/official-ish builds are checked by exact package name
     * first; if none of those are installed, fall back to whatever app (if
     * any) is registered to handle Telegram's tg:// deep-link scheme, using
     * that app's own real package name and label. Only the first match is
     * reported, since a device could plausibly have more than one. */
    private fun detectTelegramClient(): TelegramClientInfo? =
        OFFICIAL_TELEGRAM_CANDIDATES.firstOrNull { isAnyPackageInstalled(it.packageName) }
            ?: detectThirdPartyTelegramClient()

    private fun detectThirdPartyTelegramClient(): TelegramClientInfo? {
        val packageManager = getApplication<Application>().packageManager
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("$TELEGRAM_URI_SCHEME://resolve"))

        val resolvedPackages = try {
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(probeIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(probeIntent, 0)
            }
            resolveInfos.mapNotNull { it.activityInfo?.packageName }.distinct()
        } catch (_: Exception) {
            emptyList()
        }

        val officialPackageNames = OFFICIAL_TELEGRAM_CANDIDATES.map { it.packageName }.toSet()
        val packageName = resolvedPackages.firstOrNull { it !in officialPackageNames } ?: return null

        val label = try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        return TelegramClientInfo(TelegramClientKind.ThirdParty, label, packageName)
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setTelegramUserId(userId: String) {
        viewModelScope.launch { settingsRepository.setTelegramUserId(userId) }
    }

    fun setUpdateChecksEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUpdateChecksEnabled(enabled) }
    }

    /** Best-effort lookup so Settings can show "message @bot_username" --
     * failures just leave the username unknown, never surfaced as an error. */
    fun fetchBotUsername() {
        viewModelScope.launch {
            _botUsername.value = try {
                val response = RetrofitProvider.apiFor(settings.value.serverUrl).getBotInfo()
                if (response.isSuccessful) response.body()?.username else null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun togglePinned(packId: String) {
        val current = packs.value.firstOrNull { it.id == packId } ?: return
        viewModelScope.launch { packRepository.setPinned(packId, !current.isPinned) }
    }

    fun deletePack(packId: String) {
        viewModelScope.launch { packRepository.deletePack(packId) }
    }

    fun loadPreview(input: String) {
        lastPreviewInput = input
        // The sticker order/contents could differ from any earlier preview
        // of "the same" pack (edited server-side), so any previously picked
        // custom selection is no longer trustworthy -- start clean.
        _customSelection.value = null
        _importPreview.value = ImportPreviewUiState.Loading
        viewModelScope.launch {
            _importPreview.value = when (val result = packRepository.previewTelegramPack(settings.value.serverUrl, input)) {
                is PreviewResult.Loaded -> ImportPreviewUiState.Loaded(
                    shortName = result.preview.shortName,
                    title = result.preview.title,
                    totalStickerCount = result.preview.totalStickerCount,
                    partCount = result.preview.partCount,
                    stickers = result.preview.stickers,
                    emojis = result.preview.emojis,
                    warning = result.preview.warning,
                )
                is PreviewResult.Error -> ImportPreviewUiState.Error(result.message)
            }
        }
    }

    fun resetPreview() {
        _importPreview.value = ImportPreviewUiState.Idle
        _customSelection.value = null
    }

    /** Enters custom-selection mode for the currently loaded preview, with
     * everything selected by default. A no-op if already in that mode
     * (e.g. returning to the picker screen shouldn't reset taps). */
    fun beginCustomSelection() {
        val preview = _importPreview.value as? ImportPreviewUiState.Loaded ?: return
        if (_customSelection.value == null) {
            _customSelection.value = preview.stickers.map { it.id }.toSet()
        }
    }

    fun toggleCustomSticker(id: String) {
        _customSelection.value = _customSelection.value?.let { current ->
            if (id in current) current - id else current + id
        }
    }

    fun setCustomSelectionAll(selected: Boolean) {
        val preview = _importPreview.value as? ImportPreviewUiState.Loaded ?: return
        _customSelection.value = if (selected) preview.stickers.map { it.id }.toSet() else emptySet()
    }

    /** Kicks off the real import for whatever is currently checked in the
     * custom picker. */
    fun startImportCustom(): String {
        val selected = _customSelection.value ?: emptySet()
        val packId = UUID.randomUUID().toString()
        runOperation(packId) {
            packRepository.importAndConvertCustom(packId, settings.value.serverUrl, lastPreviewInput, selected)
        }
        return packId
    }

    /** Generates a pack id, kicks off the real fetch-and-convert flow for
     * the given part (0-based; only relevant when the source pack has more
     * than 30 stickers and was split into parts), and returns the id
     * immediately so the caller can navigate to it. */
    fun startImport(input: String, partIndex: Int = 0): String {
        val packId = UUID.randomUUID().toString()
        runOperation(packId) { packRepository.importAndConvert(packId, settings.value.serverUrl, input, partIndex) }
        return packId
    }

    fun createPack(items: List<PickedMediaItem>, title: String, shortName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val packId = packRepository.createPack(items, title, shortName)
            onCreated(packId)
        }
    }

    fun startPublish(packId: String, pushToTelegram: Boolean, addToWhatsapp: Boolean) {
        runOperation(packId) {
            packRepository.publishPack(
                packId,
                pushToTelegram,
                addToWhatsapp,
                settings.value.serverUrl,
                settings.value.telegramUserId,
            )
        }
    }

    /** Pull-to-refresh on My Packs: re-checks every eligible imported pack
     * against Telegram and flags any that drifted. A no-op (but still
     * settles the spinner) when the user has turned off pack updates in
     * Settings, or while a check is already in flight. */
    fun refreshMyPacks() {
        if (_isRefreshingPacks.value) return
        if (!settings.value.updateChecksEnabled) return
        viewModelScope.launch {
            _isRefreshingPacks.value = true
            try {
                packRepository.checkForUpdates(settings.value.serverUrl)
            } catch (_: Exception) {
                // Swallowed -- a failed sweep just means no dots update this
                // time; the user can pull to refresh again.
            } finally {
                _isRefreshingPacks.value = false
            }
        }
    }

    /** "Update" on the yellow-dot prompt: re-imports the pack from scratch
     * under the same pack id, discarding any custom selection -- defensively
     * clears leftover import-preview/custom-selection state first, since
     * this reuses the same conversion flow/progress UI as a fresh import. */
    fun requestPackUpdate(packId: String) {
        resetPreview()
        runOperation(packId) { packRepository.applyPackUpdate(packId, settings.value.serverUrl) }
    }

    fun disableUpdatesForPack(packId: String) {
        viewModelScope.launch { packRepository.setUpdateCheckEnabled(packId, false) }
    }

    fun refreshWhatsappAdded(packId: String) {
        viewModelScope.launch { packRepository.refreshWhatsappAdded(packId) }
    }

    fun addToWhatsappIntent(packId: String, packTitle: String, business: Boolean): Intent =
        packRepository.buildAddToWhatsappIntent(packId, packTitle, business)

    private fun runOperation(packId: String, flowFactory: () -> kotlinx.coroutines.flow.Flow<PackOperationProgress>) {
        if (_conversion.value.packId == packId && _conversion.value.isRunning) return

        operationJob?.cancel()
        _conversion.value = ConversionUiState(packId = packId, stage = "Starting", isRunning = true)
        operationJob = viewModelScope.launch {
            flowFactory().collect { progress ->
                _conversion.value = progress.toUiState(packId)
            }
        }
    }

    private fun PackOperationProgress.toUiState(packId: String): ConversionUiState = when (this) {
        is PackOperationProgress.Progress -> ConversionUiState(
            packId = packId,
            stage = stage,
            progress = fraction,
            isRunning = true,
        )
        is PackOperationProgress.Complete -> ConversionUiState(
            packId = packId,
            stage = "Ready",
            progress = 1f,
            isRunning = false,
            isComplete = true,
        )
        is PackOperationProgress.Failed -> ConversionUiState(
            packId = packId,
            stage = "Failed",
            isRunning = false,
            errorMessage = message,
        )
    }

    private fun isAnyPackageInstalled(vararg packageNames: String): Boolean {
        val packageManager = getApplication<Application>().packageManager
        return packageNames.any { packageName ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
