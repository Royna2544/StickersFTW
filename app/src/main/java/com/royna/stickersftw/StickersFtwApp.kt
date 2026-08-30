package com.royna.stickersftw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.data.ForkPackResult
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.CreatePackSubmissionResult
import com.royna.stickersftw.ui.ImportPreviewUiState
import com.royna.stickersftw.ui.MediaPreparationPurpose
import com.royna.stickersftw.ui.components.DuplicatePackOverwriteDialog
import com.royna.stickersftw.ui.components.WhatsappBlockedDialog
import com.royna.stickersftw.ui.components.ExpandableActionFab
import com.royna.stickersftw.ui.components.MixedPackChoiceDialog
import com.royna.stickersftw.ui.components.ReimportUpdatedPackDialog
import com.royna.stickersftw.ui.components.RemixPackDialog
import com.royna.stickersftw.ui.screens.ConversionScreen
import com.royna.stickersftw.ui.screens.ConvertScreen
import com.royna.stickersftw.ui.screens.CropMediaScreen
import com.royna.stickersftw.ui.screens.CreatePackScreen
import com.royna.stickersftw.ui.screens.CustomStickerPickerScreen
import com.royna.stickersftw.ui.screens.ImportPackScreen
import com.royna.stickersftw.ui.screens.MyPacksScreen
import com.royna.stickersftw.ui.screens.PackDetailScreen
import com.royna.stickersftw.ui.screens.PackUpdateDiffScreen
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.ui.screens.ShareTargetScreen
import com.royna.stickersftw.ui.screens.TrimVideoScreen
import com.royna.stickersftw.ui.screens.SettingsScreen
import com.royna.stickersftw.ui.screens.StickerGridScreen
import com.royna.stickersftw.ui.screens.StickerEditorPendingUndo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield

private object Routes {
    const val Convert = "convert"
    const val Packs = "packs"
    const val Settings = "settings"
    const val Import = "import"
    const val ImportCustom = "import/custom"
    const val Create = "create"
    const val Detail = "pack/{packId}"
    const val Conversion = "conversion/{packId}"
    const val Grid = "pack/{packId}/grid"
    const val UpdateDiff = "pack/{packId}/update"
    const val ShareTarget = "share"

    fun detail(packId: String) = "pack/$packId"
    fun conversion(packId: String) = "conversion/$packId"
    fun grid(packId: String) = "pack/$packId/grid"
    fun updateDiff(packId: String) = "pack/$packId/update"
}

private data class MainDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private data class RemixPromptRequest(
    val packTitle: String,
    val result: CompletableDeferred<String?>,
)

private data class EditablePackTarget(
    val packId: String,
    val rowIdMap: Map<Long, Long>,
    val wasForked: Boolean,
)

private data class MutationReplay(
    val target: EditablePackTarget,
    val succeeded: Boolean,
)

private val mainDestinations = listOf(
    MainDestination(Routes.Convert, R.string.nav_convert, Icons.Rounded.SwapHoriz),
    MainDestination(Routes.Packs, R.string.nav_my_packs, Icons.Rounded.Inventory2),
    MainDestination(Routes.Settings, R.string.nav_settings, Icons.Rounded.Settings),
)

private fun NavHostController.navigateToMainDestination(route: String) {
    if (currentDestination?.route == route) return

    navigate(route) {
        // Convert is the root destination. Removing everything above it makes
        // returning to the Convert tab deterministic instead of relying on a
        // saved secondary back stack.
        popUpTo(Routes.Convert) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

/** Prefer the regular WhatsApp app; fall back to Business only if that's
 * the one actually installed. */
private fun preferBusinessWhatsapp(installedApps: InstalledAppsState): Boolean =
    !installedApps.whatsappInstalled && installedApps.whatsappBusinessInstalled

@Composable
fun StickersFtwApp(
    viewModel: AppViewModel,
    pendingPackId: String? = null,
    onPendingPackIdConsumed: () -> Unit = {},
    sharedMedia: List<PickedMediaItem> = emptyList(),
    onSharedMediaConsumed: (List<PickedMediaItem>) -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied is fine -- the background operation still runs, it just
          won't be able to post progress notifications (PackOperationNotifier
          already checks the permission before every notify() call). */ }
    val requestNotificationPermissionIfNeeded = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val forceRefreshUpToDateMessage = stringResource(R.string.force_refresh_up_to_date)
    val forceRefreshUpdateFoundMessage = stringResource(R.string.force_refresh_update_found)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val currentPackIds = packs.map(StickerPack::id)
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val conversion by viewModel.conversion.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val customSelection by viewModel.customSelection.collectAsStateWithLifecycle()
    val customPickerThumbnails by viewModel.customPickerThumbnails.collectAsStateWithLifecycle()
    val botUsername by viewModel.botUsername.collectAsStateWithLifecycle()
    val pendingNavigation by viewModel.pendingNavigation.collectAsStateWithLifecycle()
    val pendingCreateNavigation by viewModel.pendingCreateNavigation.collectAsStateWithLifecycle()
    val sharedDeliveryInFlight by viewModel.sharedDeliveryInFlight.collectAsStateWithLifecycle()
    val pendingSharedMediaNavigation by viewModel.pendingSharedMediaNavigation.collectAsStateWithLifecycle()
    val mediaPreparationActive by viewModel.mediaPreparationActive.collectAsStateWithLifecycle()
    val preparedMediaCompletion by viewModel.preparedMediaCompletion.collectAsStateWithLifecycle()
    val mediaPreparationDeliveryRevision by
        viewModel.mediaPreparationDeliveryRevision.collectAsStateWithLifecycle()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsStateWithLifecycle()
    val whatsappBlocked by viewModel.whatsappBlocked.collectAsStateWithLifecycle()
    val reimportUpdatedPackPrompt by viewModel.reimportUpdatedPackPrompt.collectAsStateWithLifecycle()
    val reconversionCheckPackId by viewModel.reconversionCheckPackId.collectAsStateWithLifecycle()
    val trimRequest by viewModel.trimRequest.collectAsStateWithLifecycle()
    val cropRequest by viewModel.cropRequest.collectAsStateWithLifecycle()
    val mixedPackQuestion by viewModel.mixedPackQuestion.collectAsStateWithLifecycle()
    val busyMessage by viewModel.busyMessage.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = mainDestinations.any { it.route == currentRoute }
    val showActionFab = currentRoute == Routes.Convert || currentRoute == Routes.Packs
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var remixPrompt by remember { mutableStateOf<RemixPromptRequest?>(null) }
    var remixInFlight by remember { mutableStateOf(false) }
    val latestSharedMedia by rememberUpdatedState(sharedMedia)
    var pendingRemixUndoPackId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemixUndoKind by rememberSaveable { mutableStateOf<String?>(null) }
    val remixFailedMessage = stringResource(R.string.remix_pack_failed)

    // WhatsApp can remove packs while FTW is in the background. Re-check
    // presence whenever the app resumes; this is passive and never advances
    // the revision acknowledged by an explicit Add-to-WhatsApp result.
    LifecycleResumeEffect(currentPackIds) {
        if (currentPackIds.isNotEmpty()) viewModel.refreshWhatsappAdded(currentPackIds)
        onPauseOrDispose { }
    }

    suspend fun requestRemixName(packTitle: String): String? {
        if (remixPrompt != null) return null
        val result = CompletableDeferred<String?>()
        remixPrompt = RemixPromptRequest(packTitle, result)
        return try {
            result.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (remixPrompt?.result === result) {
                remixPrompt = null
                remixInFlight = false
            }
            throw cancelled
        }
    }

    fun finishRemixFlow() {
        remixPrompt = null
        remixInFlight = false
    }

    suspend fun editableTarget(
        pack: StickerPack,
        sourceRowIds: Collection<Long> = emptyList(),
    ): EditablePackTarget? {
        if (!pack.requiresLocalRemix) {
            return EditablePackTarget(
                packId = pack.id,
                rowIdMap = sourceRowIds.associateWith { it },
                wasForked = false,
            )
        }
        val title = requestRemixName(pack.title) ?: return null
        val fork: ForkPackResult = viewModel.forkPackForLocalEdits(pack.id, title)
            ?: run {
                android.widget.Toast.makeText(
                    context,
                    remixFailedMessage,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                finishRemixFlow()
                return null
            }
        return EditablePackTarget(
            packId = fork.newPackId,
            rowIdMap = fork.rowIdMap,
            wasForked = true,
        )
    }

    suspend fun replayPackMutation(
        pack: StickerPack,
        sourceRowIds: Collection<Long> = emptyList(),
        mutation: suspend (EditablePackTarget) -> Boolean,
    ): MutationReplay? {
        var target: EditablePackTarget? = null
        var settled = false
        try {
            target = editableTarget(pack, sourceRowIds) ?: return null
            val succeeded = mutation(target)
            if (target.wasForked) {
                if (!succeeded) {
                    viewModel.discardUnmodifiedLocalRemix(target.packId)
                }
                finishRemixFlow()
            }
            settled = true
            return MutationReplay(target, succeeded)
        } finally {
            if (!settled) {
                target?.takeIf(EditablePackTarget::wasForked)?.let {
                    viewModel.discardUnmodifiedLocalRemix(it.packId)
                }
                if (target?.wasForked == true || remixInFlight) finishRemixFlow()
            }
        }
    }

    fun showRemixDestination(packId: String, openGrid: Boolean) {
        navController.navigate(Routes.detail(packId)) {
            popUpTo(Routes.Detail) { inclusive = true }
            launchSingleTop = true
        }
        if (openGrid) navController.navigate(Routes.grid(packId))
    }

    /** Media range/crop is retained by the ViewModel, while every object that
     * can safely navigate or open a remix dialog belongs to this composition.
     * Claim the data here and release it if this owner is canceled by
     * recreation; a replacement composition will claim the same completion.
     * Once a foreground operation accepts the files, finish makes the action
     * a one-shot and transfers file ownership to that operation. */
    LaunchedEffect(preparedMediaCompletion, mediaPreparationDeliveryRevision) {
        val completion = preparedMediaCompletion ?: return@LaunchedEffect
        if (completion.purpose == MediaPreparationPurpose.Create) {
            // Create owns an internal draft list and claims this completion in
            // its destination composable below.
            return@LaunchedEffect
        }
        if (!viewModel.claimPreparedMedia(completion.generation)) return@LaunchedEffect

        var handedToService = false
        try {
            suspend fun editGridSticker(packId: String, rowId: Long) {
                val prepared = completion.preparedMedia.singleOrNull() ?: return
                val sourcePack = packs.firstOrNull { it.id == packId } ?: return
                if (!viewModel.ensureEditorOperationAvailable()) return
                val replay = replayPackMutation(sourcePack, listOf(rowId)) { target ->
                    val targetRowId = target.rowIdMap[rowId] ?: return@replayPackMutation false
                    viewModel.startStickerEdit(
                        target.packId,
                        targetRowId,
                        prepared,
                    ).also { handedToService = it }
                }
                if (replay?.succeeded == true && replay.target.wasForked) {
                    showRemixDestination(replay.target.packId, openGrid = true)
                }
            }

            when (val purpose = completion.purpose) {
                is MediaPreparationPurpose.ShareTargetAdd -> {
                    if (latestSharedMedia !== purpose.sourceMedia) return@LaunchedEffect
                    val sourcePack = packs.firstOrNull { it.id == purpose.packId }
                        ?: return@LaunchedEffect
                    if (!viewModel.ensureEditorOperationAvailable()) return@LaunchedEffect
                    val replay = replayPackMutation(sourcePack) { target ->
                        if (latestSharedMedia !== purpose.sourceMedia) {
                            false
                        } else {
                            viewModel.addStickersToPack(
                                target.packId,
                                completion.preparedMedia,
                            ).also { handedToService = it }
                        }
                    }
                    if (
                        replay?.succeeded == true &&
                        latestSharedMedia === purpose.sourceMedia
                    ) {
                        onSharedMediaConsumed(purpose.sourceMedia)
                        navController.navigate(Routes.conversion(replay.target.packId)) {
                            popUpTo(Routes.ShareTarget) { inclusive = true }
                        }
                    }
                }

                is MediaPreparationPurpose.PackDetailAdd -> {
                    val sourcePack = packs.firstOrNull { it.id == purpose.packId }
                        ?: return@LaunchedEffect
                    if (!viewModel.ensureEditorOperationAvailable()) return@LaunchedEffect
                    val replay = replayPackMutation(sourcePack) { target ->
                        viewModel.addStickersToPack(
                            target.packId,
                            completion.preparedMedia,
                        ).also { handedToService = it }
                    }
                    if (replay?.succeeded == true) {
                        if (replay.target.wasForked) {
                            showRemixDestination(replay.target.packId, openGrid = false)
                        }
                        navController.navigate(Routes.conversion(replay.target.packId))
                    }
                }

                is MediaPreparationPurpose.GridEdit ->
                    editGridSticker(purpose.packId, purpose.rowId)

                is MediaPreparationPurpose.GridReplace ->
                    editGridSticker(purpose.packId, purpose.rowId)

                MediaPreparationPurpose.Create -> Unit
            }
        } finally {
            when {
                handedToService -> viewModel.finishPreparedMedia(
                    completion.generation,
                    handedOff = true,
                )
                currentCoroutineContext().isActive -> viewModel.finishPreparedMedia(
                    completion.generation,
                    handedOff = false,
                )
                else -> viewModel.releasePreparedMedia(completion.generation)
            }
        }
    }

    val whatsappAvailable = installedApps.whatsappInstalled || installedApps.whatsappBusinessInstalled
    val useBusinessWhatsapp = preferBusinessWhatsapp(installedApps)

    LaunchedEffect(currentRoute) { fabExpanded = false }

    // Delete/reorder on a freshly forked pack resolves its Snackbar on the
    // remix destination. Saveable state makes this navigation resume after a
    // configuration change instead of leaving a target-only pending edit
    // hidden behind the source pack's grid.
    LaunchedEffect(pendingRemixUndoPackId, pendingRemixUndoKind) {
        val targetPackId = pendingRemixUndoPackId ?: return@LaunchedEffect
        if (pendingRemixUndoKind == null) return@LaunchedEffect
        val visibleEntry = navController.currentBackStackEntry
        val visiblePackId = visibleEntry?.arguments?.getString("packId")
        val isTargetGrid = visibleEntry?.destination?.route == Routes.Grid &&
            visiblePackId == targetPackId
        if (!isTargetGrid) {
            yield()
            showRemixDestination(targetPackId, openGrid = true)
        }
    }

    // Reopens straight onto the pack's Conversion screen when the user taps
    // a "Run in background" notification -- that screen already renders
    // whichever of progress/success/failure is current, so no separate
    // per-state routing is needed.
    LaunchedEffect(pendingPackId) {
        if (pendingPackId != null) {
            // A notification can replace Create while its retained range/crop
            // request is active. Cancel first so a later completion cannot be
            // left without a destination owner.
            viewModel.cancelTrim()
            navController.navigate(Routes.conversion(pendingPackId)) {
                launchSingleTop = true
            }
            onPendingPackIdConsumed()
        }
    }

    LaunchedEffect(busyMessage) {
        if (busyMessage != null) {
            android.widget.Toast.makeText(context, busyMessage, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeBusyMessage()
        }
    }

    // The delivery generation is a retained one-shot. Keying navigation on
    // the media list itself would replay it on every Activity recreation and
    // throw the user out of an in-progress Create form.
    LaunchedEffect(pendingSharedMediaNavigation) {
        val generation = pendingSharedMediaNavigation ?: return@LaunchedEffect
        viewModel.cancelTrim()
        navController.navigate(Routes.ShareTarget) { launchSingleTop = true }
        viewModel.consumePendingSharedMediaNavigation(generation)
    }

    LaunchedEffect(pendingNavigation) {
        val packId = pendingNavigation
        if (packId != null) {
            // Asynchronous import/reconversion decisions navigate here after
            // their service request has actually been accepted.
            viewModel.cancelTrim()
            navController.navigate(Routes.conversion(packId)) {
                // Fresh imports replace their form. A retained reconversion
                // preflight can finish while Pack Detail is current, where
                // popping up to an unrelated Import route either does nothing
                // useful or removes too much restored navigation state.
                if (currentRoute == Routes.Import || currentRoute == Routes.ImportCustom) {
                    popUpTo(Routes.Import) { inclusive = true }
                }
                launchSingleTop = true
            }
            viewModel.consumePendingNavigation()
        }
    }

    LaunchedEffect(pendingCreateNavigation, sharedMedia, sharedDeliveryInFlight) {
        val packId = pendingCreateNavigation ?: return@LaunchedEffect
        // While a replacement SEND is copying, sharedMedia still points at
        // the old Create batch. Do not mistake that old list for a settled
        // newer delivery and throw away the only lifecycle-safe navigation.
        if (sharedDeliveryInFlight) return@LaunchedEffect
        // A newer ACTION_SEND wins the foreground. Its batch remains owned
        // and visible; the started Create is still in My Packs/notification.
        if (sharedMedia.isEmpty()) {
            navController.navigate(Routes.conversion(packId)) {
                popUpTo(Routes.Create) { inclusive = true }
            }
        }
        viewModel.consumePendingCreateNavigation()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (showActionFab) {
                ExpandableActionFab(
                    expanded = fabExpanded,
                    onToggle = { fabExpanded = !fabExpanded },
                    onImport = { navController.navigate(Routes.Import) },
                    onCreate = { navController.navigate(Routes.Create) },
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    mainDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigateToMainDestination(destination.route)
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = stringResource(destination.labelRes))
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                            // Material3 1.4.0 changed the selected label's
                            // token from onSurface to secondary, which paints
                            // it Telegram blue here -- jarring next to the
                            // lavender indicator, and not a colour this app
                            // ever chose. Pinning both selected colours to the
                            // primary/primaryContainer pair also fixes the
                            // icon, which was still taking its colour from
                            // onSecondaryContainer despite the indicator
                            // behind it having been switched to
                            // primaryContainer.
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Convert,
        ) {
            composable(Routes.Convert) {
                val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
                ConvertScreen(
                    settings = settings,
                    installedApps = installedApps,
                    packs = packs,
                    serverStatus = serverStatus,
                    onCheckServerConnection = viewModel::checkServerConnection,
                    onOpenPack = { navController.navigate(Routes.detail(it)) },
                    onSeeAll = { navController.navigate(Routes.Packs) },
                    contentPadding = scaffoldPadding,
                )
            }
            composable(Routes.Packs) {
                val isRefreshingPacks by viewModel.isRefreshingPacks.collectAsStateWithLifecycle()
                MyPacksScreen(
                    packs = packs,
                    onOpenPack = { navController.navigate(Routes.detail(it)) },
                    onTogglePinned = viewModel::togglePinned,
                    onDeletePack = viewModel::deletePack,
                    isRefreshing = isRefreshingPacks,
                    onRefresh = viewModel::refreshMyPacks,
                    onRequestUpdate = { packId ->
                        navController.navigate(Routes.updateDiff(packId))
                    },
                    onDisableUpdates = viewModel::disableUpdatesForPack,
                    contentPadding = scaffoldPadding,
                    activeConversion = conversion,
                    onResumeConversion = { navController.navigate(Routes.conversion(it)) },
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    settings = settings,
                    botUsername = botUsername,
                    onFetchBotUsername = viewModel::fetchBotUsername,
                    onSetBackendMode = viewModel::setBackendMode,
                    onCheckAndSaveServerUrl = viewModel::checkAndSaveServerUrl,
                    onForceSaveServerUrl = viewModel::forceSaveServerUrl,
                    onCheckAndSaveBotToken = viewModel::checkAndSaveBotToken,
                    onForceSaveBotToken = viewModel::forceSaveBotToken,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetConversionBias = viewModel::setConversionBias,
                    onSetTelegramUserId = viewModel::setTelegramUserId,
                    onSetUpdateChecksEnabled = viewModel::setUpdateChecksEnabled,
                    onSetPingTestsEnabled = viewModel::setPingTestsEnabled,
                    contentPadding = scaffoldPadding,
                )
            }
            composable(Routes.Import) {
                ImportPackScreen(
                    previewState = importPreview,
                    onLoadPreview = viewModel::loadPreview,
                    onResetPreview = viewModel::resetPreview,
                    onBack = { navController.popBackStack() },
                    onImport = { input, partIndex -> viewModel.startImport(input, partIndex) },
                    onPickCustom = {
                        viewModel.beginCustomSelection()
                        viewModel.loadCustomPickerThumbnails()
                        navController.navigate(Routes.ImportCustom)
                    },
                    initialInput = viewModel.lastPreviewInput,
                )
            }
            composable(Routes.ImportCustom) {
                val loaded = importPreview as? ImportPreviewUiState.Loaded
                CustomStickerPickerScreen(
                    thumbnailUrls = customPickerThumbnails,
                    stickers = loaded?.stickers.orEmpty(),
                    selectedIds = customSelection.orEmpty(),
                    onToggle = viewModel::toggleCustomSticker,
                    onSetAll = viewModel::setCustomSelectionAll,
                    onBack = { navController.popBackStack() },
                    onDownload = { viewModel.startImportCustom() },
                )
            }
            composable(Routes.ShareTarget) {
                ShareTargetScreen(
                    packs = packs,
                    sharedCount = sharedMedia.size,
                    enabled = !mediaPreparationActive && !sharedDeliveryInFlight,
                    onCreateNew = { navController.navigate(Routes.Create) },
                    onAddToPack = { id ->
                        if (mediaPreparationActive || sharedDeliveryInFlight) {
                            return@ShareTargetScreen
                        }
                        if (packs.none { it.id == id }) return@ShareTargetScreen
                        val mediaSnapshot = sharedMedia
                        viewModel.prepareMedia(
                            purpose = MediaPreparationPurpose.ShareTargetAdd(id, mediaSnapshot),
                            items = mediaSnapshot,
                        )
                    },
                    onBack = {
                        viewModel.cancelTrim()
                        onSharedMediaConsumed(sharedMedia)
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.Create) { entry ->
                // A later ACTION_SEND may recompose the app while this Create
                // submission is still being persisted. Keep the exact batch
                // that seeded this destination so success never consumes the
                // newer delivery by mistake.
                val initialSharedBatch = remember(entry) { sharedMedia }
                val createCompletion = preparedMediaCompletion?.takeIf {
                    it.purpose == MediaPreparationPurpose.Create
                }
                CreatePackScreen(
                    enabled = !sharedDeliveryInFlight,
                    initialItems = initialSharedBatch,
                    onStartRetainedMediaPreparation = { items ->
                        viewModel.prepareMedia(MediaPreparationPurpose.Create, items)
                    },
                    preparedMediaGeneration = createCompletion?.generation,
                    preparedMediaDeliveryRevision = mediaPreparationDeliveryRevision,
                    preparedMediaItems = createCompletion?.preparedMedia.orEmpty(),
                    onClaimPreparedMedia = viewModel::claimPreparedMedia,
                    onFinishPreparedMedia = viewModel::finishPreparedMedia,
                    onCancelMediaPreparation = viewModel::cancelTrim,
                    onDiscardMedia = viewModel::discardPreparedMedia,
                    onBack = { navController.popBackStack() },
                    botUsername = botUsername,
                    onPublish = { items, title, shortName, pushToTelegram, addToWhatsapp, onResult ->
                        viewModel.createPack(
                            items = items,
                            title = title,
                            shortName = shortName,
                            pushToTelegram = pushToTelegram,
                            addToWhatsapp = addToWhatsapp,
                        ) { result ->
                            // Transfer/discard screen-owned prepared files
                            // before navigation can dispose the destination.
                            onResult(result)
                            if (result is CreatePackSubmissionResult.Started) {
                                onSharedMediaConsumed(initialSharedBatch)
                            }
                        }
                    },
                )
            }
            composable(Routes.Detail) { entry ->
                val id = entry.arguments?.getString("packId")
                val pack = packs.firstOrNull { it.id == id }
                PackDetailScreen(
                    pack = pack,
                    whatsappAvailable = whatsappAvailable,
                    whatsappBusiness = useBusinessWhatsapp,
                    onBack = { navController.popBackStack() },
                    onTogglePinned = viewModel::togglePinned,
                    onDelete = {
                        viewModel.deletePack(it)
                        navController.popBackStack()
                    },
                    onBuildWhatsappIntent = {
                        pack?.let { viewModel.addToWhatsappIntent(it.id, it.title, useBusinessWhatsapp) }
                    },
                    onWhatsappResult = { confirmed, expectedRevision, launchedBusiness ->
                        pack?.let {
                            if (confirmed) {
                                viewModel.acknowledgeWhatsappInstall(
                                    it.id,
                                    expectedRevision,
                                    launchedBusiness,
                                )
                            } else {
                                viewModel.refreshWhatsappAdded(it.id)
                            }
                        }
                    },
                    onRefreshWhatsapp = viewModel::refreshWhatsappAdded,
                    onPushToTelegram = { packId ->
                        if (viewModel.startPublish(packId, pushToTelegram = true, addToWhatsapp = false)) {
                            navController.navigate(Routes.conversion(packId))
                        }
                    },
                    onDeleteFromTelegram = { packId, onDone ->
                        viewModel.deletePackAndTelegramSet(packId) { result ->
                            when (result) {
                                is StickerPackRepository.DeleteTelegramResult.Success -> onDone(true, null)
                                is StickerPackRepository.DeleteTelegramResult.Failed -> onDone(false, result.reason)
                            }
                        }
                    },
                    onForceRefreshFromTelegram = { packId, onDone ->
                        viewModel.forceRefreshPack(packId) { result ->
                            onDone(
                                when (result) {
                                    is StickerPackRepository.ForceRefreshResult.UpToDate -> forceRefreshUpToDateMessage
                                    is StickerPackRepository.ForceRefreshResult.UpdateAvailable -> forceRefreshUpdateFoundMessage
                                    is StickerPackRepository.ForceRefreshResult.Failed -> result.reason
                                },
                            )
                        }
                    },
                    onViewAllStickers = { navController.navigate(Routes.grid(it)) },
                    onAddStickers = { id, items ->
                        if (pack == null) return@PackDetailScreen
                        viewModel.prepareMedia(
                            purpose = MediaPreparationPurpose.PackDetailAdd(id),
                            items = items,
                        )
                    },
                    reconversionCheckInProgress = reconversionCheckPackId == pack?.id,
                    onReconvert = viewModel::requestPackReconversion,
                )
            }
            composable(Routes.UpdateDiff) { entry ->
                val id = entry.arguments?.getString("packId").orEmpty()
                val pack = packs.firstOrNull { it.id == id }
                val diff by viewModel.updateDiff.collectAsStateWithLifecycle()
                LaunchedEffect(id) { viewModel.loadUpdateDiff(id) }
                PackUpdateDiffScreen(
                    packTitle = pack?.title.orEmpty(),
                    state = diff,
                    onBack = {
                        viewModel.clearUpdateDiff()
                        navController.popBackStack()
                    },
                    onConfirm = {
                        viewModel.clearUpdateDiff()
                        if (viewModel.requestPackUpdate(id)) {
                            navController.navigate(Routes.conversion(id)) {
                                popUpTo(Routes.UpdateDiff) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable(Routes.Grid) { entry ->
                val id = entry.arguments?.getString("packId").orEmpty()
                val pack = packs.firstOrNull { it.id == id }
                val stickers by remember(id) { viewModel.observePackStickers(id) }
                    .collectAsStateWithLifecycle(initialValue = emptyList())

                fun mappedRows(
                    target: EditablePackTarget,
                    sourceRowIds: List<Long>,
                ): List<Long>? = sourceRowIds.map { target.rowIdMap[it] ?: return null }

                StickerGridScreen(
                    packTitle = pack?.title.orEmpty(),
                    stickers = stickers,
                    onBack = { navController.popBackStack() },
                    onEdit = { rowId ->
                        viewModel.prepareStickerEdit(id, rowId)
                    },
                    onReplace = { rowId, item ->
                        viewModel.prepareMedia(
                            purpose = MediaPreparationPurpose.GridReplace(id, rowId),
                            items = listOf(item),
                        )
                    },
                    onUpdateEmoji = { rowId, emojis ->
                        val sourcePack = pack
                        if (sourcePack == null) {
                            null
                        } else {
                            val replay = replayPackMutation(sourcePack, listOf(rowId)) { target ->
                                target.rowIdMap[rowId]?.let {
                                    viewModel.updateStickerEmojis(target.packId, it, emojis)
                                } ?: false
                            }
                            if (replay?.succeeded == true && replay.target.wasForked) {
                                showRemixDestination(replay.target.packId, openGrid = true)
                            }
                            replay?.succeeded
                        }
                    },
                    onSetTray = { rowId ->
                        val sourcePack = pack
                        if (sourcePack == null) {
                            null
                        } else {
                            val replay = replayPackMutation(sourcePack, listOf(rowId)) { target ->
                                target.rowIdMap[rowId]?.let {
                                    viewModel.setTraySticker(target.packId, it)
                                } ?: false
                            }
                            if (replay?.succeeded == true && replay.target.wasForked) {
                                showRemixDestination(replay.target.packId, openGrid = true)
                            }
                            replay?.succeeded
                        }
                    },
                    onDelete = { rowId ->
                        val sourcePack = pack
                        if (sourcePack == null) {
                            null
                        } else {
                            val replay = replayPackMutation(sourcePack, listOf(rowId)) { target ->
                                target.rowIdMap[rowId]?.let {
                                    viewModel.deleteSticker(target.packId, it)
                                } ?: false
                            }
                            if (replay?.succeeded == true && replay.target.wasForked) {
                                pendingRemixUndoPackId = replay.target.packId
                                pendingRemixUndoKind = StickerEditorPendingUndo.Delete.name
                                null
                            } else {
                                replay?.succeeded
                            }
                        }
                    },
                    onReorder = { rowIds ->
                        val sourcePack = pack
                        if (sourcePack == null) {
                            null
                        } else {
                            val replay = replayPackMutation(sourcePack, rowIds) { target ->
                                mappedRows(target, rowIds)?.let {
                                    viewModel.reorderStickers(target.packId, it)
                                } ?: false
                            }
                            if (replay?.succeeded == true && replay.target.wasForked) {
                                pendingRemixUndoPackId = replay.target.packId
                                pendingRemixUndoKind = StickerEditorPendingUndo.Reorder.name
                                null
                            } else {
                                replay?.succeeded
                            }
                        }
                    },
                    onUndo = { viewModel.undoLastPackEdit(id) },
                    onFinalizeUndo = { viewModel.finalizeLastPackEdit(id) },
                    onUndoResolved = {
                        if (pendingRemixUndoPackId == id) {
                            pendingRemixUndoPackId = null
                            pendingRemixUndoKind = null
                        }
                    },
                    pendingUndo = pendingRemixUndoKind
                        ?.let { name -> StickerEditorPendingUndo.entries.firstOrNull { it.name == name } }
                        ?.takeIf { pendingRemixUndoPackId == id },
                    onPendingUndoConsumed = {
                        if (pendingRemixUndoPackId == id) {
                            pendingRemixUndoPackId = null
                            pendingRemixUndoKind = null
                        }
                    },
                    isBusy = conversion.isRunning && conversion.packId == id,
                    busyStage = conversion.stage,
                    busyProgress = conversion.progress,
                )
            }
            composable(Routes.Conversion) { entry ->
                val id = entry.arguments?.getString("packId")
                val pack = packs.firstOrNull { it.id == id }
                val loadedPreview = importPreview as? ImportPreviewUiState.Loaded
                // Only offer to convert other parts for a fetch-side import
                // (not a Create-pack publish, which reuses this same screen)
                // of a pack that was actually split into more than one part.
                val showConvertOtherParts = pack?.origin == PackOrigin.Imported &&
                    (loadedPreview?.partCount ?: 1) > 1
                val splitPack = conversion.splitPackId?.let { id -> packs.firstOrNull { it.id == id } }
                // Looking at the result is the notification's whole purpose
                // served, so it goes as soon as this screen shows one. Gated on
                // the operation being this pack's: the shared conversion state
                // still describes whatever ran last, and an unrelated pack's
                // notification is not this screen's to clear.
                val finishedPackId = id?.takeIf {
                    conversion.packId == it &&
                        (conversion.isComplete || conversion.errorMessage != null)
                }
                LaunchedEffect(finishedPackId) {
                    if (finishedPackId != null) viewModel.dismissOperationNotification(finishedPackId)
                }
                ConversionScreen(
                    pack = pack,
                    state = conversion,
                    splitPack = splitPack,
                    whatsappBusiness = useBusinessWhatsapp,
                    onBuildSplitWhatsappIntent = {
                        splitPack?.let { viewModel.addToWhatsappIntent(it.id, it.title, useBusinessWhatsapp) }
                    },
                    onSplitWhatsappResult = { confirmed, expectedRevision, launchedBusiness ->
                        splitPack?.let {
                            if (confirmed) {
                                viewModel.acknowledgeWhatsappInstall(
                                    it.id,
                                    expectedRevision,
                                    launchedBusiness,
                                )
                            } else {
                                viewModel.refreshWhatsappAdded(it.id)
                            }
                        }
                    },
                    whatsappAvailable = whatsappAvailable,
                    onBack = { navController.popBackStack() },
                    onOpenPacks = {
                        navController.navigate(Routes.Packs) {
                            popUpTo(Routes.Convert)
                            launchSingleTop = true
                        }
                    },
                    onBuildWhatsappIntent = {
                        pack?.let { viewModel.addToWhatsappIntent(it.id, it.title, useBusinessWhatsapp) }
                    },
                    onWhatsappResult = { confirmed, expectedRevision, launchedBusiness ->
                        pack?.let {
                            if (confirmed) {
                                viewModel.acknowledgeWhatsappInstall(
                                    it.id,
                                    expectedRevision,
                                    launchedBusiness,
                                )
                            } else {
                                viewModel.refreshWhatsappAdded(it.id)
                            }
                        }
                    },
                    showConvertOtherParts = showConvertOtherParts,
                    onConvertOtherParts = { navController.navigate(Routes.Import) },
                    onRunInBackground = {
                        requestNotificationPermissionIfNeeded()
                        pack?.let { viewModel.runInBackground(it.id, it.title) }
                    },
                )
            }
        }
    }

    // This is an overlay rather than a navigation destination. The screen
    // that launched a trim (especially Create, with its local picked-item
    // list) stays composed underneath, so confirming cannot discard that
    // state or restart its initial-media effect.
    trimRequest?.let { request ->
        Dialog(
            onDismissRequest = {
                viewModel.cancelTrim()
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            TrimVideoScreen(
                mediaUri = request.mediaUri,
                durationMs = request.durationMs,
                startMs = request.startMs,
                selectedDurationMs = request.selectedDurationMs,
                position = request.position,
                total = request.total,
                onRangeChanged = viewModel::setTrimRange,
                onConfirm = viewModel::confirmTrim,
                onBack = {
                    viewModel.cancelTrim()
                },
            )
        }
    }

    cropRequest?.let { request ->
        Dialog(
            onDismissRequest = {
                viewModel.cancelTrim()
            },
            properties = DialogProperties(
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            CropMediaScreen(
                previewFrame = request.preview,
                position = request.position,
                total = request.total,
                onConfirm = viewModel::confirmCrop,
                onKeepFull = viewModel::keepFullImage,
                onBack = {
                    viewModel.cancelTrim()
                },
            )
        }
    }

    mixedPackQuestion?.let { question ->
        MixedPackChoiceDialog(
            animatedCount = question.animatedCount,
            staticCount = question.staticCount,
            onSplit = { viewModel.answerMixedPack(splitByType = true) },
            onKeepTogether = { viewModel.answerMixedPack(splitByType = false) },
        )
    }

    whatsappBlocked?.let { blocked ->
        WhatsappBlockedDialog(
            packTitle = blocked.packTitle,
            violations = blocked.violations,
            onDismiss = viewModel::dismissWhatsappBlocked,
        )
    }

    duplicatePrompt?.let { prompt ->
        DuplicatePackOverwriteDialog(
            packTitle = prompt.packTitle,
            onOverwrite = prompt.onConfirm,
            onCancel = prompt.onReject,
        )
    }

    reimportUpdatedPackPrompt?.let { prompt ->
        ReimportUpdatedPackDialog(
            packTitle = prompt.packTitle,
            onYes = viewModel::confirmReimportUpdatedPack,
            onNo = viewModel::declineReimportAndReconvertPack,
        )
    }

    remixPrompt?.let { prompt ->
        RemixPackDialog(
            packTitle = prompt.packTitle,
            isCreating = remixInFlight,
            onConfirm = { title ->
                if (!prompt.result.isCompleted) {
                    remixInFlight = true
                    prompt.result.complete(title)
                }
            },
            onCancel = {
                if (!prompt.result.isCompleted) prompt.result.complete(null)
                finishRemixFlow()
            },
        )
    }
}
