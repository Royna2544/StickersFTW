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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.ImportPreviewUiState
import com.royna.stickersftw.ui.components.DuplicatePackOverwriteDialog
import com.royna.stickersftw.ui.components.ExpandableActionFab
import com.royna.stickersftw.ui.components.MixedPackChoiceDialog
import com.royna.stickersftw.ui.screens.ConversionScreen
import com.royna.stickersftw.ui.screens.ConvertScreen
import com.royna.stickersftw.ui.screens.CreatePackScreen
import com.royna.stickersftw.ui.screens.CustomStickerPickerScreen
import com.royna.stickersftw.ui.screens.ImportPackScreen
import com.royna.stickersftw.ui.screens.MyPacksScreen
import com.royna.stickersftw.ui.screens.PackDetailScreen
import com.royna.stickersftw.ui.screens.PackUpdateDiffScreen
import com.royna.stickersftw.ui.screens.SettingsScreen
import com.royna.stickersftw.ui.screens.StickerGridScreen

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
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val conversion by viewModel.conversion.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val customSelection by viewModel.customSelection.collectAsStateWithLifecycle()
    val customPickerThumbnails by viewModel.customPickerThumbnails.collectAsStateWithLifecycle()
    val botUsername by viewModel.botUsername.collectAsStateWithLifecycle()
    val pendingNavigation by viewModel.pendingNavigation.collectAsStateWithLifecycle()
    val duplicatePrompt by viewModel.duplicatePrompt.collectAsStateWithLifecycle()
    val mixedPackQuestion by viewModel.mixedPackQuestion.collectAsStateWithLifecycle()
    val busyMessage by viewModel.busyMessage.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = mainDestinations.any { it.route == currentRoute }
    val showActionFab = currentRoute == Routes.Convert || currentRoute == Routes.Packs
    var fabExpanded by rememberSaveable { mutableStateOf(false) }

    val whatsappAvailable = installedApps.whatsappInstalled || installedApps.whatsappBusinessInstalled
    val useBusinessWhatsapp = preferBusinessWhatsapp(installedApps)

    LaunchedEffect(currentRoute) { fabExpanded = false }

    // Reopens straight onto the pack's Conversion screen when the user taps
    // a "Run in background" notification -- that screen already renders
    // whichever of progress/success/failure is current, so no separate
    // per-state routing is needed.
    LaunchedEffect(pendingPackId) {
        if (pendingPackId != null) {
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

    // Fires once an import/update actually starts running (immediately for
    // a fresh pack, or after the user answers "Overwrite?" for a duplicate)
    // -- can't navigate at the call site since a duplicate has to wait on
    // that answer first.
    LaunchedEffect(pendingNavigation) {
        val packId = pendingNavigation
        if (packId != null) {
            navController.navigate(Routes.conversion(packId)) {
                popUpTo(Routes.Import) { inclusive = true }
            }
            viewModel.consumePendingNavigation()
        }
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
            composable(Routes.Create) {
                CreatePackScreen(
                    onBack = { navController.popBackStack() },
                    botUsername = botUsername,
                    onPublish = { items, title, shortName, pushToTelegram, addToWhatsapp ->
                        viewModel.createPack(items, title, shortName) { packId ->
                            if (viewModel.startPublish(packId, pushToTelegram, addToWhatsapp)) {
                                navController.navigate(Routes.conversion(packId)) {
                                    popUpTo(Routes.Create) { inclusive = true }
                                }
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
                    onBack = { navController.popBackStack() },
                    onTogglePinned = viewModel::togglePinned,
                    onDelete = {
                        viewModel.deletePack(it)
                        navController.popBackStack()
                    },
                    onBuildWhatsappIntent = {
                        pack?.let { viewModel.addToWhatsappIntent(it.id, it.title, useBusinessWhatsapp) }
                    },
                    onWhatsappResult = { pack?.let { viewModel.refreshWhatsappAdded(it.id) } },
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
                StickerGridScreen(
                    packTitle = pack?.title.orEmpty(),
                    stickers = stickers,
                    onBack = { navController.popBackStack() },
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
                ConversionScreen(
                    pack = pack,
                    state = conversion,
                    splitPack = splitPack,
                    onBuildSplitWhatsappIntent = {
                        splitPack?.let { viewModel.addToWhatsappIntent(it.id, it.title, useBusinessWhatsapp) }
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
                    onWhatsappResult = { pack?.let { viewModel.refreshWhatsappAdded(it.id) } },
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

    mixedPackQuestion?.let { question ->
        MixedPackChoiceDialog(
            animatedCount = question.animatedCount,
            staticCount = question.staticCount,
            onSplit = { viewModel.answerMixedPack(splitByType = true) },
            onKeepTogether = { viewModel.answerMixedPack(splitByType = false) },
        )
    }

    duplicatePrompt?.let { prompt ->
        DuplicatePackOverwriteDialog(
            packTitle = prompt.packTitle,
            onOverwrite = prompt.onConfirm,
            onCancel = prompt.onReject,
        )
    }
}
