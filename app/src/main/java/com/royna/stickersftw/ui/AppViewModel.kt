package com.royna.stickersftw.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.royna.stickersftw.R
import com.royna.stickersftw.SharedMedia
import com.royna.stickersftw.data.SettingsRepository
import com.royna.stickersftw.conversion.PackViolation
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
import java.util.concurrent.atomic.AtomicBoolean
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
/** Why WhatsApp would refuse a pack, held so the app can say so itself
 * instead of forwarding the user to a toast that explains nothing. */
data class WhatsappBlocked(
    val packTitle: String,
    val violations: List<PackViolation>,
)

data class DuplicatePackPrompt(
    val packTitle: String,
    val onConfirm: () -> Unit,
    val onReject: () -> Unit,
)

/** Retained result of a reconversion preflight that found newer Telegram
 * content. Keeping only stable pack data here means Activity recreation can
 * redraw the same decision without repeating the network request or starting
 * either operation on the user's behalf. */
data class ReimportUpdatedPackPrompt(
    val packId: String,
    val packTitle: String,
)

internal fun StickerPack.matchesImportedPart(shortName: String, partIndex: Int): Boolean =
    origin == PackOrigin.Imported &&
        telegramSetName == shortName &&
        (importPartIndex == partIndex || sourcePartIndex == partIndex)

/** Synchronous gate between a Create button click and the coroutine that
 * persists its pack. The operation-availability check is part of acquisition
 * so a rejected attempt does not consume the user's ability to retry. */
internal class CreateSubmissionGate {
    private val accepted = AtomicBoolean(false)

    fun tryAccept(operationAvailable: Boolean): Boolean =
        operationAvailable && accepted.compareAndSet(false, true)

    fun release() {
        accepted.set(false)
    }
}

/** Keeps ACTION_SEND ownership stable while its bytes are copied and across
 * Activity recreation. All calls are made on the ViewModel's main thread. */
internal class SharedMediaDeliveryGate {
    private var generation = 0L
    private var active = false
    private var inFlight = false

    fun beginInitial(): Long? {
        if (active) return null
        return begin()
    }

    fun beginReplacement(): Long = begin()

    private fun begin(): Long {
        active = true
        inFlight = true
        return ++generation
    }

    fun complete(candidate: Long, hasMedia: Boolean): Boolean {
        if (candidate != generation) return false
        inFlight = false
        active = hasMedia
        return true
    }

    fun tryConsume(): Boolean {
        if (!active || inFlight) return false
        active = false
        return true
    }

    fun invalidate() {
        generation++
        active = false
        inFlight = false
    }

    val isActive: Boolean get() = active
    val isInFlight: Boolean get() = inFlight
}

/** Retains a completed media preparation until the currently visible
 * composition claims it. A claim is released on lifecycle cancellation, so a
 * replacement composition can continue it; [finish] makes the action a
 * one-shot once it has either handed the files off or deliberately failed. */
internal class MediaPreparationDeliveryGate {
    private var generation = 0L
    private var active = false
    private var published = false
    private var claimed = false
    private val _deliveryRevision = MutableStateFlow(0L)
    val deliveryRevision: StateFlow<Long> = _deliveryRevision.asStateFlow()

    fun begin(): Long? {
        if (active) return null
        active = true
        published = false
        claimed = false
        return ++generation
    }

    fun publish(candidate: Long): Boolean {
        if (!active || published || candidate != generation) return false
        published = true
        return true
    }

    fun tryClaim(candidate: Long): Boolean {
        if (!active || !published || claimed || candidate != generation) return false
        claimed = true
        return true
    }

    fun release(candidate: Long): Boolean {
        if (!active || !published || !claimed || candidate != generation) return false
        claimed = false
        // A replacement composition may already have observed the unchanged
        // completion and failed its first claim while the old owner was still
        // active. This observable revision wakes it for another attempt.
        _deliveryRevision.value++
        return true
    }

    fun finish(candidate: Long): Boolean {
        if (!active || candidate != generation) return false
        active = false
        published = false
        claimed = false
        return true
    }

    fun invalidate() {
        generation++
        active = false
        published = false
        claimed = false
    }

    val isActive: Boolean get() = active
}

/** Unwinds a preparation generation for both ordinary source-load failures
 * and cancellation, while preserving structured cancellation for callers. */
internal suspend fun <T> guardedMediaPreparationSource(
    onFailure: () -> Unit,
    load: suspend () -> T,
): T? = try {
    load()
} catch (cancelled: CancellationException) {
    onFailure()
    throw cancelled
} catch (_: Exception) {
    onFailure()
    null
}

sealed interface MediaPreparationPurpose {
    data class ShareTargetAdd(
        val packId: String,
        /** Exact retained ACTION_SEND batch selected on the destination screen. */
        val sourceMedia: List<PickedMediaItem>,
    ) : MediaPreparationPurpose

    data object Create : MediaPreparationPurpose
    data class PackDetailAdd(val packId: String) : MediaPreparationPurpose
    data class GridEdit(val packId: String, val rowId: Long) : MediaPreparationPurpose
    data class GridReplace(val packId: String, val rowId: Long) : MediaPreparationPurpose
}

data class PreparedMediaCompletion(
    val generation: Long,
    val purpose: MediaPreparationPurpose,
    /** Independently materialised range/crop recipe owned until [finish]. */
    val preparedMedia: List<PickedMediaItem>,
)

sealed interface CreatePackSubmissionResult {
    data class Started(val packId: String) : CreatePackSubmissionResult
    data object Failed : CreatePackSubmissionResult
}

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
    private val createSubmissionGate = CreateSubmissionGate()

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

    private val sharedMediaDelivery = SharedMediaDeliveryGate()
    private val _sharedMedia = MutableStateFlow<List<PickedMediaItem>>(emptyList())
    val sharedMedia: StateFlow<List<PickedMediaItem>> = _sharedMedia.asStateFlow()
    private val _sharedDeliveryActive = MutableStateFlow(false)
    val sharedDeliveryActive: StateFlow<Boolean> = _sharedDeliveryActive.asStateFlow()
    private val _sharedDeliveryInFlight = MutableStateFlow(false)
    val sharedDeliveryInFlight: StateFlow<Boolean> = _sharedDeliveryInFlight.asStateFlow()
    private val _pendingSharedMediaNavigation = MutableStateFlow<Long?>(null)
    val pendingSharedMediaNavigation: StateFlow<Long?> = _pendingSharedMediaNavigation.asStateFlow()

    private val mediaPreparationDelivery = MediaPreparationDeliveryGate()
    private val _mediaPreparationActive = MutableStateFlow(false)
    val mediaPreparationActive: StateFlow<Boolean> = _mediaPreparationActive.asStateFlow()
    private val _preparedMediaCompletion = MutableStateFlow<PreparedMediaCompletion?>(null)
    val preparedMediaCompletion: StateFlow<PreparedMediaCompletion?> =
        _preparedMediaCompletion.asStateFlow()
    val mediaPreparationDeliveryRevision: StateFlow<Long> =
        mediaPreparationDelivery.deliveryRevision

    fun consumePendingSharedMediaNavigation(generation: Long) {
        if (_pendingSharedMediaNavigation.value == generation) {
            _pendingSharedMediaNavigation.value = null
        }
    }

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

    private val _whatsappBlocked = MutableStateFlow<WhatsappBlocked?>(null)
    val whatsappBlocked: StateFlow<WhatsappBlocked?> = _whatsappBlocked.asStateFlow()

    private val _duplicatePrompt = MutableStateFlow<DuplicatePackPrompt?>(null)
    val duplicatePrompt: StateFlow<DuplicatePackPrompt?> = _duplicatePrompt.asStateFlow()

    private val _reimportUpdatedPackPrompt = MutableStateFlow<ReimportUpdatedPackPrompt?>(null)
    val reimportUpdatedPackPrompt: StateFlow<ReimportUpdatedPackPrompt?> =
        _reimportUpdatedPackPrompt.asStateFlow()

    /** The one pack whose Telegram source is being force-checked before an
     * old local conversion is rebuilt. The detail screen uses this retained
     * id to disable the action and show progress across recompositions. */
    private val _reconversionCheckPackId = MutableStateFlow<String?>(null)
    val reconversionCheckPackId: StateFlow<String?> = _reconversionCheckPackId.asStateFlow()

    /** One-shot signal for the UI to navigate to a just-started operation's
     * Conversion screen. Duplicate imports and reconversion preflights both
     * wait on asynchronous decisions, so neither can navigate synchronously
     * at the original click site. */
    private val _pendingNavigation = MutableStateFlow<String?>(null)
    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()

    fun consumePendingNavigation() {
        _pendingNavigation.value = null
    }

    /** Create can finish its Room write after the Activity that submitted it
     * rotates. Navigation therefore belongs to the retained ViewModel rather
     * than the submitting composition's callback. */
    private val _pendingCreateNavigation = MutableStateFlow<String?>(null)
    val pendingCreateNavigation: StateFlow<String?> = _pendingCreateNavigation.asStateFlow()

    fun consumePendingCreateNavigation() {
        _pendingCreateNavigation.value = null
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

    /** Checks the live Telegram set before rebuilding an older app-version's
     * local output. Unchanged content can use the durable originals directly;
     * changed content requires an explicit re-import decision first. */
    fun requestPackReconversion(packId: String) {
        val pack = packs.value.firstOrNull { it.id == packId } ?: return
        if (!pack.needsReconversion || _reconversionCheckPackId.value != null ||
            _reimportUpdatedPackPrompt.value != null
        ) {
            return
        }
        if (PackOperationController.isRunning) {
            _busyMessage.value = getApplication<Application>().getString(R.string.err_operation_already_running)
            return
        }

        _reconversionCheckPackId.value = packId
        viewModelScope.launch {
            try {
                when (val result = packRepository.forceRefreshPack(packId, settings.value.backendConfig)) {
                    StickerPackRepository.ForceRefreshResult.UpToDate -> {
                        val current = packs.value.firstOrNull {
                            it.id == packId && it.needsReconversion
                        } ?: return@launch
                        if (
                            start(
                                PackOperationRequest.Reconvert(
                                    packId = current.id,
                                    packTitle = current.title,
                                ),
                            )
                        ) {
                            _pendingNavigation.value = current.id
                        }
                    }
                    StickerPackRepository.ForceRefreshResult.UpdateAvailable -> {
                        val current = packs.value.firstOrNull {
                            it.id == packId && it.needsReconversion
                        } ?: return@launch
                        _reimportUpdatedPackPrompt.value = ReimportUpdatedPackPrompt(
                            packId = current.id,
                            packTitle = current.title,
                        )
                    }
                    is StickerPackRepository.ForceRefreshResult.Failed -> {
                        _busyMessage.value = result.reason
                    }
                }
            } finally {
                if (_reconversionCheckPackId.value == packId) {
                    _reconversionCheckPackId.value = null
                }
            }
        }
    }

    /** Yes on the upstream-change prompt: import Telegram's current snapshot
     * under the same pack id. The normal update finalization supplies the
     * conversion-version stamp and monotonic WhatsApp revision bump. */
    fun confirmReimportUpdatedPack() {
        val prompt = _reimportUpdatedPackPrompt.value ?: return
        _reimportUpdatedPackPrompt.value = null
        resetPreview()
        if (
            start(
                PackOperationRequest.Update(
                    packId = prompt.packId,
                    packTitle = prompt.packTitle,
                ),
            )
        ) {
            _pendingNavigation.value = prompt.packId
        }
    }

    /** No on the upstream-change prompt keeps the imported snapshot exactly
     * as it is, but still rebuilds those cached originals with this app
     * version. The Telegram update flag remains available for later review. */
    fun declineReimportAndReconvertPack() {
        val prompt = _reimportUpdatedPackPrompt.value ?: return
        _reimportUpdatedPackPrompt.value = null
        if (
            start(
                PackOperationRequest.Reconvert(
                    packId = prompt.packId,
                    packTitle = prompt.packTitle,
                ),
            )
        ) {
            _pendingNavigation.value = prompt.packId
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
        val allPacks = packs.value
        val matches = allPacks.filter { it.matchesImportedPart(shortName, partIndex) }
        val animatedSuffix = getApplication<Application>().getString(R.string.pack_animated_suffix)
        val hasLegacySplitSibling = matches.any { base ->
            allPacks.any { sibling ->
                sibling.id != base.id &&
                    sibling.origin == PackOrigin.Imported &&
                    sibling.telegramSetName == shortName &&
                    sibling.importPartIndex == StickerPackRepository.ANIMATED_SPLIT_PART_INDEX &&
                    sibling.title == base.title + animatedSuffix
            }
        }
        if (matches.size > 1 || hasLegacySplitSibling) {
            _busyMessage.value = getApplication<Application>().getString(
                R.string.err_split_part_duplicate_import,
            )
            return
        }
        val existing = matches.singleOrNull()
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

    /** Accepts or rejects the submission synchronously, before the repository
     * coroutine can yield. This prevents two rapid clicks from inserting two
     * packs with the same prepared media. */
    fun createPack(
        items: List<PickedMediaItem>,
        title: String,
        shortName: String,
        pushToTelegram: Boolean,
        addToWhatsapp: Boolean,
        onResult: (CreatePackSubmissionResult) -> Unit,
    ): Boolean {
        val operationAvailable = !PackOperationController.isRunning
        if (!createSubmissionGate.tryAccept(operationAvailable)) {
            if (!operationAvailable) {
                _busyMessage.value = getApplication<Application>().getString(R.string.err_operation_already_running)
            }
            return false
        }
        viewModelScope.launch {
            var packId: String? = null
            var handedToService = false
            var result: CreatePackSubmissionResult = CreatePackSubmissionResult.Failed
            try {
                val createdPackId = UUID.randomUUID().toString()
                packId = createdPackId
                packRepository.createPack(items, title, shortName, createdPackId)
                handedToService = start(
                    PackOperationRequest.Publish(
                        packId = createdPackId,
                        packTitle = title,
                        pushToTelegram = pushToTelegram,
                        addToWhatsapp = addToWhatsapp,
                    ),
                )
                if (handedToService) {
                    result = CreatePackSubmissionResult.Started(createdPackId)
                    _pendingCreateNavigation.value = createdPackId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _busyMessage.value = error.message
                    ?: getApplication<Application>().getString(R.string.err_import_failed)
            } finally {
                if (!handedToService) {
                    packId?.let { unstartedPackId ->
                        try {
                            withContext(NonCancellable) {
                                packRepository.discardUnstartedCreatedPack(unstartedPackId)
                            }
                        } catch (_: Exception) {
                            // The retry remains safe because prepared inputs
                            // are never deleted by this cleanup. Startup will
                            // mark any surviving Building row as interrupted.
                        }
                    }
                }
                // Release before notifying the UI so an async rejection can
                // be retried immediately from the callback's next frame.
                createSubmissionGate.release()
                onResult(result)
            }
        }
        return true
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
    ): Boolean {
        val purpose = MediaPreparationPurpose.GridEdit(packId, rowId)
        val generation = beginMediaPreparation() ?: return false
        viewModelScope.launch {
            val item = guardedMediaPreparationSource(
                onFailure = { failMediaPreparation(generation) },
            ) {
                packRepository.finalizeLastPackEdit(packId)
                packRepository.editableStickerItem(packId, rowId)
            }
            if (item == null) {
                failMediaPreparation(generation)
                return@launch
            }
            runMediaPreparation(generation, purpose, listOf(item))
        }
        return true
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

    // ---- Retained ACTION_SEND intake ---------------------------------

    /** Claims the Activity's launch Intent only when no delivery is already
     * retained. A configuration replacement therefore reuses the exact same
     * batch (and list identity) instead of copying it again. */
    fun ingestInitialSharedMedia(intent: Intent?) {
        val delivery = intent ?: return
        if (!delivery.isMediaShare()) return
        val generation = sharedMediaDelivery.beginInitial() ?: return
        _sharedDeliveryActive.value = true
        _sharedDeliveryInFlight.value = true
        ingestSharedMedia(delivery, generation)
    }

    /** A real singleTask delivery supersedes any older editor, even if its
     * copy is still in flight. Stale copy results clean only themselves. */
    fun replaceSharedMedia(intent: Intent?) {
        val delivery = intent ?: return
        if (!delivery.isMediaShare()) return
        val generation = sharedMediaDelivery.beginReplacement()
        _sharedDeliveryActive.value = true
        _sharedDeliveryInFlight.value = true
        _pendingSharedMediaNavigation.value = null
        cancelTrim()
        ingestSharedMedia(delivery, generation)
    }

    private fun ingestSharedMedia(intent: Intent, generation: Long) {
        viewModelScope.launch {
            val ingested = try {
                SharedMedia.ingest(intent, getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (!sharedMediaDelivery.complete(generation, ingested.isNotEmpty())) {
                SharedMedia.discard(ingested, getApplication())
                return@launch
            }

            val replaced = _sharedMedia.value
            _sharedMedia.value = ingested
            _sharedDeliveryActive.value = sharedMediaDelivery.isActive
            _sharedDeliveryInFlight.value = sharedMediaDelivery.isInFlight
            _pendingSharedMediaNavigation.value = generation.takeIf { ingested.isNotEmpty() }
            if (replaced !== ingested) SharedMedia.discard(replaced, getApplication())
        }
    }

    /** Consumes only the exact visible batch, and never while a newer Intent
     * is still copying. This prevents a late callback from clearing the new
     * Activity Intent or deleting its source batch. */
    fun consumeSharedMedia(items: List<PickedMediaItem>): Boolean {
        if (_sharedMedia.value !== items || !sharedMediaDelivery.tryConsume()) return false
        _sharedMedia.value = emptyList()
        _sharedDeliveryActive.value = false
        _sharedDeliveryInFlight.value = false
        _pendingSharedMediaNavigation.value = null
        SharedMedia.discard(items, getApplication())
        return true
    }

    // ---- Preparing picked media (trim + crop) -------------------------

    private val trimCoordinator = MediaTrimCoordinator(application)
    val trimRequest: StateFlow<TrimRequest?> = trimCoordinator.request
    val cropRequest: StateFlow<CropRequest?> = trimCoordinator.cropRequest

    /** Materialises picked media, selects a range for every video, then offers
     * a non-destructive crop. Completion is retained as data instead of an UI
     * lambda so the current composition can claim it after recreation. */
    fun prepareMedia(
        purpose: MediaPreparationPurpose,
        items: List<PickedMediaItem>,
    ): Boolean {
        val generation = beginMediaPreparation() ?: return false
        viewModelScope.launch { runMediaPreparation(generation, purpose, items) }
        return true
    }

    private fun beginMediaPreparation(): Long? {
        val generation = mediaPreparationDelivery.begin() ?: return null
        _mediaPreparationActive.value = true
        return generation
    }

    private suspend fun runMediaPreparation(
        generation: Long,
        purpose: MediaPreparationPurpose,
        items: List<PickedMediaItem>,
    ) {
        val started = try {
            trimCoordinator.begin(items) { prepared ->
                if (mediaPreparationDelivery.publish(generation)) {
                    _preparedMediaCompletion.value = PreparedMediaCompletion(
                        generation = generation,
                        purpose = purpose,
                        preparedMedia = prepared,
                    )
                } else {
                    trimCoordinator.discardResolved(prepared)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!started) failMediaPreparation(generation)
    }

    private fun failMediaPreparation(generation: Long) {
        if (mediaPreparationDelivery.finish(generation)) {
            _mediaPreparationActive.value = false
        }
    }

    fun claimPreparedMedia(generation: Long): Boolean =
        mediaPreparationDelivery.tryClaim(generation)

    fun releasePreparedMedia(generation: Long) {
        mediaPreparationDelivery.release(generation)
    }

    /** Resolves a claimed completion exactly once. [handedOff] means either a
     * foreground operation or the Create draft owns the files; every other
     * outcome reclaims them here. */
    fun finishPreparedMedia(generation: Long, handedOff: Boolean) {
        val pending = _preparedMediaCompletion.value
        if (pending?.generation != generation || !mediaPreparationDelivery.finish(generation)) return
        _preparedMediaCompletion.value = null
        _mediaPreparationActive.value = false
        if (!handedOff) trimCoordinator.discardResolved(pending.preparedMedia)
    }

    private fun invalidatePreparedMedia() {
        val pending = _preparedMediaCompletion.value
        mediaPreparationDelivery.invalidate()
        _preparedMediaCompletion.value = null
        _mediaPreparationActive.value = false
        pending?.let { trimCoordinator.discardResolved(it.preparedMedia) }
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

    fun cancelTrim() {
        invalidatePreparedMedia()
        trimCoordinator.cancel()
    }

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

    /** Returns the intent only if WhatsApp would actually accept the pack.
     *
     * WhatsApp's own rejection is one toast that names neither the rule nor
     * the sticker, so handing over a pack known to be invalid spends the
     * user's time to tell them nothing. When the pack fails, this reports what
     * is wrong through [whatsappBlocked] and returns null, which the button
     * already treats as "do not launch". */
    suspend fun addToWhatsappIntent(packId: String, packTitle: String, business: Boolean): Intent? {
        val violations = packRepository.whatsappViolations(packId)
        if (violations.isNotEmpty()) {
            _whatsappBlocked.value = WhatsappBlocked(packTitle, violations)
            return null
        }
        return packRepository.buildAddToWhatsappIntent(packId, packTitle, business)
    }

    fun dismissWhatsappBlocked() {
        _whatsappBlocked.value = null
    }

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

    override fun onCleared() {
        sharedMediaDelivery.invalidate()
        cancelTrim()
        SharedMedia.discard(_sharedMedia.value, getApplication())
        _sharedMedia.value = emptyList()
        _sharedDeliveryActive.value = false
        _sharedDeliveryInFlight.value = false
        _pendingSharedMediaNavigation.value = null
        super.onCleared()
    }

    private fun Intent?.isMediaShare(): Boolean =
        this?.action == Intent.ACTION_SEND || this?.action == Intent.ACTION_SEND_MULTIPLE

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
