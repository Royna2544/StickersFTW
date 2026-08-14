package com.royna.stickersftw.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.royna.stickersftw.R
import com.royna.stickersftw.data.SettingsRepository
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.data.ForkPackResult
import com.royna.stickersftw.data.ThemeModeCache
import com.royna.stickersftw.data.model.PackUpdateDiffResult
import com.royna.stickersftw.data.model.PreviewResult
import com.royna.stickersftw.data.model.PreviewSticker
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.BackendMode
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.ServerConnectionStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramClientInfo
import com.royna.stickersftw.model.TelegramClientKind
import com.royna.stickersftw.model.ThemeMode
import com.royna.stickersftw.model.backendConfig
import com.royna.stickersftw.network.ApiResult
import com.royna.stickersftw.network.TelegramBackendConfig
import com.royna.stickersftw.network.TelegramBackendProvider
import com.royna.stickersftw.notifications.PackOperationNotifier
import com.royna.stickersftw.operation.MixedPackQuestion
import com.royna.stickersftw.operation.PackOperationController
import com.royna.stickersftw.operation.PackOperationRequest
import com.royna.stickersftw.operation.PackOperationService
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown as a full-screen prompt when an import request matches a pack/part
 * already in My Packs -- [onConfirm] overwrites it in place (same pack id,
 * old stickers/files replaced), [onReject] cancels the import outright
 * rather than silently creating a duplicate entry. */
data class DuplicatePackPrompt(
    val packTitle: String,
    val onConfirm: () -> Unit,
    val onReject: () -> Unit,
)

sealed class ServerUrlSaveResult {
    data object Saved : ServerUrlSaveResult()
    data object ConnectionFailed : ServerUrlSaveResult()
}

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
        // DataStore's first emission is asynchronous, so the first frames
        // compose against this value. Seeding the theme from the synchronous
        // cache keeps them on the user's actual choice instead of briefly
        // resolving ThemeMode.System against the system setting.
        initialValue = AppSettings(themeMode = ThemeModeCache.read(application)),
    )

    val packs: StateFlow<List<StickerPack>> = packRepository.observePacks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _installedApps = MutableStateFlow(InstalledAppsState())
    val installedApps: StateFlow<InstalledAppsState> = _installedApps.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerConnectionStatus>(ServerConnectionStatus.Unknown)
    val serverStatus: StateFlow<ServerConnectionStatus> = _serverStatus.asStateFlow()
    private var serverCheckJob: Job? = null

    /** Drives the Convert page's server status row -- a no-op when ping
     * tests are disabled in Settings, leaving the status "Unknown" rather
     * than claiming a reachability it never actually checked. */
    fun checkServerConnection() {
        if (!settings.value.pingTestsEnabled) {
            _serverStatus.value = ServerConnectionStatus.Unknown
            return
        }
        serverCheckJob?.cancel()
        serverCheckJob = viewModelScope.launch {
            _serverStatus.value = ServerConnectionStatus.Checking
            val reachable = packRepository.pingServer(settings.value.backendConfig)
            _serverStatus.value = if (reachable) ServerConnectionStatus.Connected else ServerConnectionStatus.Failed
        }
    }

    /** Owned by [PackOperationService] via [PackOperationController], not by
     * this ViewModel: the operation outlives any Activity, so its state has
     * to as well. */
    val conversion: StateFlow<ConversionUiState> = PackOperationController.state

    /** Non-null while a conversion is waiting to be told what to do about a
     * pack holding both animated and static stickers. */
    val mixedPackQuestion: StateFlow<MixedPackQuestion?> = PackOperationController.question

    fun answerMixedPack(splitByType: Boolean) {
        PackOperationController.answerMixedPack(splitByType)
    }

    /** One-shot: set when a download/conversion/publish was rejected because
     * a different pack's operation is already running -- Telegram/the server
     * flood-limits concurrent requests, so only one pack may run at a time. */
    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    fun consumeBusyMessage() {
        _busyMessage.value = null
    }

    private val _importPreview = MutableStateFlow<ImportPreviewUiState>(ImportPreviewUiState.Idle)
    val importPreview: StateFlow<ImportPreviewUiState> = _importPreview.asStateFlow()

    private val _botUsername = MutableStateFlow<String?>(null)
    val botUsername: StateFlow<String?> = _botUsername.asStateFlow()

    /** Null while the fetch is in flight; the screen shows a spinner. Held
     * per-visit rather than cached, since the whole point is to reflect what
     * Telegram has right now. */
    private val _updateDiff = MutableStateFlow<PackUpdateDiffResult?>(null)
    val updateDiff: StateFlow<PackUpdateDiffResult?> = _updateDiff.asStateFlow()

    fun loadUpdateDiff(packId: String) {
        _updateDiff.value = null
        viewModelScope.launch {
            _updateDiff.value = packRepository.computeUpdateDiff(packId, settings.value.backendConfig)
        }
    }

    fun clearUpdateDiff() {
        _updateDiff.value = null
    }

    private val _isRefreshingPacks = MutableStateFlow(false)
    val isRefreshingPacks: StateFlow<Boolean> = _isRefreshingPacks.asStateFlow()

    /** Null means "no custom selection in progress" (the auto-split parts
     * are used instead). Set by [beginCustomSelection] once the user taps
     * "I want to pick my own", and cleared on every fresh [loadPreview]
     * call since the pack's sticker order/contents could have changed
     * server-side, which would make old selected ids stale or wrong. */
    private val _customSelection = MutableStateFlow<Set<String>?>(null)
    val customSelection: StateFlow<Set<String>?> = _customSelection.asStateFlow()

    /** Resolved lazily via [loadCustomPickerThumbnails] when the custom
     * picker screen is actually opened -- see
     * [com.royna.stickersftw.data.StickerPackRepository.resolveThumbnailUrls]
     * for why this isn't just done eagerly on every preview load. */
    private val _customPickerThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())
    val customPickerThumbnails: StateFlow<Map<String, String>> = _customPickerThumbnails.asStateFlow()

    fun loadCustomPickerThumbnails() {
        val preview = _importPreview.value as? ImportPreviewUiState.Loaded ?: return
        viewModelScope.launch {
            _customPickerThumbnails.value = packRepository.resolveThumbnailUrls(
                settings.value.backendConfig,
                preview.shortName,
                preview.stickers,
            )
        }
    }

    private val _duplicatePrompt = MutableStateFlow<DuplicatePackPrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePackPrompt?> = _duplicatePrompt.asStateFlow()

    /** One-shot signal for the UI to navigate to a just-started operation's
     * Conversion screen -- a plain return value doesn't work here since a
     * duplicate-pack import has to wait on [duplicatePrompt]'s answer
     * first, so navigation can't happen synchronously at the call site. */
    private val _pendingNavigation = MutableStateFlow<String?>(null)
    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()

    fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    /** The Telegram pack link/short-name last submitted to [loadPreview] --
     * exposed so the Import screen can re-populate its text field when
     * returning to it (e.g. via "Convert other parts of pack") instead of
     * showing a blank field under a still-loaded preview. */
    var lastPreviewInput: String = ""
        private set


    init {
        refreshInstalledApps()
        // Nothing running in this process means any pack still sitting in a
        // non-terminal state was left there by a process that went away
        // mid-conversion. Without this it stays "Downloading" forever, with
        // no way back other than deleting it.
        if (!PackOperationController.isRunning) {
            viewModelScope.launch {
                packRepository.failInterruptedOperations().forEach { packId ->
                    PackOperationNotifier.cancel(getApplication(), packId)
                }
            }
        }
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

    /** Settings' "Save" action on the server URL: pings the *new* URL first
     * (unless ping tests are disabled) so a typo/unreachable server is
     * caught before it's persisted, rather than only surfacing later as a
     * confusing failure somewhere else in the app. A failed ping does not
     * save -- [onResult] reports [ServerUrlSaveResult.ConnectionFailed] so
     * the caller can ask the user whether to save anyway. */
    fun checkAndSaveServerUrl(url: String, onResult: (ServerUrlSaveResult) -> Unit) {
        viewModelScope.launch {
            if (!settings.value.pingTestsEnabled || packRepository.pingServer(TelegramBackendConfig.ServerUrl(url))) {
                settingsRepository.setServerUrl(url)
                onResult(ServerUrlSaveResult.Saved)
            } else {
                onResult(ServerUrlSaveResult.ConnectionFailed)
            }
        }
    }

    /** "Save anyway" after [checkAndSaveServerUrl] reported a failed ping. */
    fun forceSaveServerUrl(url: String) {
        setServerUrl(url)
    }

    fun setBackendMode(mode: BackendMode) {
        viewModelScope.launch { settingsRepository.setBackendMode(mode) }
    }

    fun setBotToken(token: String) {
        settingsRepository.setBotToken(token)
    }

    /** Mirrors [checkAndSaveServerUrl]'s ping-before-save flow for the
     * bot-token dialog. */
    fun checkAndSaveBotToken(token: String, onResult: (ServerUrlSaveResult) -> Unit) {
        viewModelScope.launch {
            if (!settings.value.pingTestsEnabled || packRepository.pingServer(TelegramBackendConfig.BotToken(token))) {
                settingsRepository.setBotToken(token)
                onResult(ServerUrlSaveResult.Saved)
            } else {
                onResult(ServerUrlSaveResult.ConnectionFailed)
            }
        }
    }

    /** "Save anyway" after [checkAndSaveBotToken] reported a failed ping. */
    fun forceSaveBotToken(token: String) {
        setBotToken(token)
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setConversionBias(bias: ConversionBias) {
        viewModelScope.launch { settingsRepository.setConversionBias(bias) }
    }

    fun setTelegramUserId(userId: String) {
        viewModelScope.launch { settingsRepository.setTelegramUserId(userId) }
    }

    fun setUpdateChecksEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUpdateChecksEnabled(enabled) }
    }

    fun setPingTestsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPingTestsEnabled(enabled) }
    }

    /** Best-effort lookup so Settings can show "message @bot_username" --
     * failures just leave the username unknown, never surfaced as an error. */
    fun fetchBotUsername() {
        viewModelScope.launch {
            val result = try {
                TelegramBackendProvider.resolve(settings.value.backendConfig).getBotInfo()
            } catch (_: Exception) {
                null
            }
            _botUsername.value = (result as? ApiResult.Success)?.value?.username
        }
    }

    fun togglePinned(packId: String) {
        val current = packs.value.firstOrNull { it.id == packId } ?: return
        viewModelScope.launch { packRepository.setPinned(packId, !current.isPinned) }
    }

    fun deletePack(packId: String) {
        viewModelScope.launch { packRepository.deletePack(packId) }
    }

    fun deletePackAndTelegramSet(packId: String, onResult: (StickerPackRepository.DeleteTelegramResult) -> Unit) {
        viewModelScope.launch {
            val result = packRepository.deletePackAndTelegramSet(packId, settings.value.backendConfig)
            onResult(result)
        }
    }

    fun observePackStickers(packId: String) = packRepository.observePackStickers(packId)

    fun forceRefreshPack(packId: String, onResult: (StickerPackRepository.ForceRefreshResult) -> Unit) {
        viewModelScope.launch {
            val result = packRepository.forceRefreshPack(packId, settings.value.backendConfig)
            onResult(result)
        }
    }

    fun loadPreview(input: String) {
        lastPreviewInput = input
        // The sticker order/contents could differ from any earlier preview
        // of "the same" pack (edited server-side), so any previously picked
        // custom selection is no longer trustworthy -- start clean.
        _customSelection.value = null
        _customPickerThumbnails.value = emptyMap()
        _importPreview.value = ImportPreviewUiState.Loading
        viewModelScope.launch {
            _importPreview.value = when (val result = packRepository.previewTelegramPack(settings.value.backendConfig, input)) {
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
        _customPickerThumbnails.value = emptyMap()
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
     * custom picker. If this exact pack/slice was already imported, prompts
     * to overwrite instead of silently creating a duplicate entry -- see
     * [duplicatePrompt]. Navigation to the resulting pack's Conversion
     * screen is driven by [pendingNavigation], not a return value, since a
     * duplicate has to wait on the user's answer first. */
    fun startImportCustom() {
        val selected = _customSelection.value ?: emptySet()
        val shortName = packRepository.extractShortName(lastPreviewInput)
        startImportOrPromptOverwrite(shortName, StickerPackRepository.CUSTOM_PART_INDEX) { packId, title ->
            PackOperationRequest.ImportCustom(packId, title, lastPreviewInput, selected)
        }
    }

    /** Kicks off the real fetch-and-convert flow for the given part (0-based;
     * only relevant when the source pack has more than 30 stickers and was
     * split into parts). If this exact pack/part was already imported,
     * prompts to overwrite instead of silently creating a duplicate entry --
     * see [duplicatePrompt] and [pendingNavigation]. */
    fun startImport(input: String, partIndex: Int = 0) {
        val shortName = packRepository.extractShortName(input)
        startImportOrPromptOverwrite(shortName, partIndex) { packId, title ->
            PackOperationRequest.Import(packId, title, input, partIndex)
        }
    }

    private fun startImportOrPromptOverwrite(
        shortName: String,
        partIndex: Int,
        request: (packId: String, packTitle: String) -> PackOperationRequest,
    ) {
        val existing = packs.value.firstOrNull {
            it.origin == PackOrigin.Imported && it.telegramSetName == shortName && it.importPartIndex == partIndex
        }
        // A pack that never finished importing is not a duplicate worth
        // protecting -- since the row is now written up front, a failed or
        // interrupted attempt leaves one behind, and asking whether to
        // overwrite something the user never actually got would be nonsense.
        // Retry in place on the same id instead, so retries can't pile up
        // half-built rows.
        if (existing == null || existing.status != PackStatus.Ready) {
            val packId = existing?.id ?: UUID.randomUUID().toString()
            if (start(request(packId, existing?.title ?: shortName))) {
                _pendingNavigation.value = packId
            }
            return
        }
        _duplicatePrompt.value = DuplicatePackPrompt(
            packTitle = existing.title,
            onConfirm = {
                _duplicatePrompt.value = null
                if (start(request(existing.id, existing.title))) {
                    _pendingNavigation.value = existing.id
                }
            },
            onReject = { _duplicatePrompt.value = null },
        )
    }

    fun createPack(items: List<PickedMediaItem>, title: String, shortName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val packId = packRepository.createPack(items, title, shortName)
            onCreated(packId)
        }
    }

    fun startPublish(packId: String, pushToTelegram: Boolean, addToWhatsapp: Boolean): Boolean =
        start(
            PackOperationRequest.Publish(
                packId,
                packs.value.firstOrNull { it.id == packId }?.title.orEmpty(),
                pushToTelegram,
                addToWhatsapp,
            ),
        )

    /** Pull-to-refresh on My Packs: passively refreshes WhatsApp presence for
     * every local pack, then (when enabled) checks eligible imports against
     * Telegram. A second refresh is ignored while one is in flight. */
    fun refreshMyPacks() {
        if (_isRefreshingPacks.value) return
        viewModelScope.launch {
            _isRefreshingPacks.value = true
            try {
                try {
                    packRepository.refreshWhatsappAdded(packs.value.map { it.id })
                } catch (_: Exception) {
                    // Presence is best-effort; one unavailable provider must
                    // not prevent the independent Telegram update sweep.
                }
                if (settings.value.updateChecksEnabled) {
                    try {
                        packRepository.checkForUpdates(settings.value.backendConfig)
                    } catch (_: Exception) {
                        // A failed sweep just means no update dots change this
                        // time; the user can pull to refresh again.
                    }
                }
            } finally {
                _isRefreshingPacks.value = false
            }
        }
    }

    /** "Update" on the yellow-dot prompt: re-imports the pack from scratch
     * under the same pack id, discarding any custom selection -- defensively
     * clears leftover import-preview/custom-selection state first, since
     * this reuses the same conversion flow/progress UI as a fresh import. */
    fun requestPackUpdate(packId: String): Boolean {
        resetPreview()
        return start(
            PackOperationRequest.Update(packId, packs.value.firstOrNull { it.id == packId }?.title.orEmpty()),
        )
    }

    /** "Add stickers" on a pack that has room left. Runs on the same operation
     * pipeline as an import, so it gets the foreground service, the
     * notification and the progress screen rather than silently converting in
     * the background of whatever screen started it. */
    fun addStickersToPack(packId: String, items: List<PickedMediaItem>): Boolean {
        if (items.isEmpty()) return false
        return start(
            PackOperationRequest.AddStickers(
                packId,
                packs.value.firstOrNull { it.id == packId }?.title.orEmpty(),
                items,
            ),
        )
    }

    /** Re-opens an existing durable source with its saved range/crop recipe.
     * The UI resolves a possible linked-pack remix only after this preparation
     * finishes, so canceling range/crop cannot create an unused clone. */
    fun prepareStickerEdit(
        packId: String,
        rowId: Long,
        onReady: (PickedMediaItem) -> Unit,
    ) {
        viewModelScope.launch {
            packRepository.finalizeLastPackEdit(packId)
            val item = packRepository.editableStickerItem(packId, rowId) ?: return@launch
            trimCoordinator.begin(listOf(item)) { prepared ->
                prepared.singleOrNull()?.let(onReady)
            }
        }
    }

    fun startStickerEdit(packId: String, rowId: Long, item: PickedMediaItem): Boolean =
        start(
            PackOperationRequest.EditSticker(
                packId = packId,
                packTitle = packs.value.firstOrNull { it.id == packId }?.title.orEmpty(),
                rowId = rowId,
                item = item,
            ),
        )

    suspend fun forkPackForLocalEdits(packId: String, title: String): ForkPackResult? {
        // The fork itself belongs to the ViewModel so a configuration change
        // cannot cancel it halfway through. If the requesting screen goes
        // away, await completion and reclaim the still-unmodified clone.
        val task = viewModelScope.async { packRepository.forkPackForLocalEdits(packId, title) }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val abandoned = runCatching { task.await() }.getOrNull()
                abandoned?.let { packRepository.discardUnmodifiedLocalRemix(it.newPackId) }
            }
            throw cancelled
        }
    }

    suspend fun discardUnmodifiedLocalRemix(packId: String): Boolean =
        packRepository.discardUnmodifiedLocalRemix(packId)

    fun ensureEditorOperationAvailable(): Boolean {
        if (!PackOperationController.isRunning) return true
        _busyMessage.value = getApplication<Application>().getString(R.string.err_operation_already_running)
        return false
    }

    suspend fun updateStickerEmojis(packId: String, rowId: Long, emojis: List<String>): Boolean =
        packRepository.updateStickerEmojis(packId, rowId, emojis)

    suspend fun updateStickerEmojis(packId: String, rowId: Long, emojis: String): Boolean =
        packRepository.updateStickerEmojis(packId, rowId, emojis)

    suspend fun setTraySticker(packId: String, rowId: Long): Boolean =
        packRepository.setTraySticker(packId, rowId)

    suspend fun deleteSticker(packId: String, rowId: Long): Boolean =
        packRepository.deleteSticker(packId, rowId)

    suspend fun reorderStickers(packId: String, orderedRowIds: List<Long>): Boolean =
        packRepository.reorderStickers(packId, orderedRowIds)

    suspend fun undoLastPackEdit(packId: String) {
        packRepository.undoLastPackEdit(packId)
    }

    suspend fun finalizeLastPackEdit(packId: String) {
        packRepository.finalizeLastPackEdit(packId)
    }

    // ---- Preparing picked media (trim + crop) -------------------------

    private val trimCoordinator = MediaTrimCoordinator(application)
    val trimRequest: StateFlow<TrimRequest?> = trimCoordinator.request
    val cropRequest: StateFlow<CropRequest?> = trimCoordinator.cropRequest

    /** Materialises picked media, selects a range for every video, then offers a
     * non-destructive crop before handing the edited items to [onReady]. */
    fun prepareMedia(items: List<PickedMediaItem>, onReady: (List<PickedMediaItem>) -> Unit) {
        viewModelScope.launch { trimCoordinator.begin(items, onReady) }
    }

    fun discardPreparedMedia(items: List<PickedMediaItem>) {
        trimCoordinator.discardResolved(items)
    }

    fun setTrimRange(startMs: Long, durationMs: Long) {
        trimCoordinator.setRange(startMs, durationMs)
    }

    fun confirmTrim(startMs: Long, durationMs: Long) {
        viewModelScope.launch { trimCoordinator.confirm(startMs, durationMs) }
    }

    fun cancelTrim() = trimCoordinator.cancel()

    fun confirmCrop(crop: com.royna.stickersftw.model.MediaCrop) {
        viewModelScope.launch { trimCoordinator.confirmCrop(crop) }
    }

    fun keepFullImage() {
        viewModelScope.launch { trimCoordinator.keepFullImage() }
    }

    fun disableUpdatesForPack(packId: String) {
        viewModelScope.launch { packRepository.setUpdateCheckEnabled(packId, false) }
    }

    fun refreshWhatsappAdded(packId: String) {
        viewModelScope.launch { packRepository.refreshWhatsappAdded(packId) }
    }

    fun refreshWhatsappAdded(packIds: Collection<String>) {
        if (packIds.isEmpty()) return
        viewModelScope.launch { packRepository.refreshWhatsappAdded(packIds) }
    }

    /** Explicit Add-to-WhatsApp result path. Unlike passive refresh, this may
     * advance the acknowledged content revision after whitelist verification. */
    fun acknowledgeWhatsappInstall(packId: String, expectedRevision: Int, business: Boolean) {
        viewModelScope.launch {
            packRepository.acknowledgeWhatsappInstall(packId, expectedRevision, business)
        }
    }

    fun addToWhatsappIntent(packId: String, packTitle: String, business: Boolean): Intent =
        packRepository.buildAddToWhatsappIntent(packId, packTitle, business)

    /** "Run in background" is now only a way out of the Conversion screen:
     * [PackOperationService] runs the work and posts its notification from
     * the moment it starts, so there is nothing left to opt into. Kept as an
     * action because leaving the screen is still a thing people want to do,
     * and it's the natural moment to ask for the notification permission. */
    fun runInBackground(packId: String, packTitle: String) {
        PackOperationNotifier.ensureChannel(getApplication())
    }

    /** Only one pack may download/convert/publish at a time -- the server
     * flood-limits concurrent Telegram requests, and letting a second
     * operation silently cancel the first would orphan its progress. Returns
     * false (and sets [busyMessage]) when another pack's operation is
     * already running; the caller does nothing further in that case. */
    private fun start(request: PackOperationRequest): Boolean {
        if (!PackOperationController.canStart(request.packId)) {
            _busyMessage.value = getApplication<Application>().getString(R.string.err_operation_already_running)
            return false
        }
        PackOperationService.start(getApplication(), request)
        return true
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
