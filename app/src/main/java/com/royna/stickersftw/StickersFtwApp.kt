package com.royna.stickersftw

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.ImportPreviewUiState
import com.royna.stickersftw.ui.screens.ConversionScreen
import com.royna.stickersftw.ui.screens.ConvertScreen
import com.royna.stickersftw.ui.screens.CreatePackScreen
import com.royna.stickersftw.ui.screens.CustomStickerPickerScreen
import com.royna.stickersftw.ui.screens.ImportPackScreen
import com.royna.stickersftw.ui.screens.MyPacksScreen
import com.royna.stickersftw.ui.screens.PackDetailScreen
import com.royna.stickersftw.ui.screens.SettingsScreen

private object Routes {
    const val Convert = "convert"
    const val Packs = "packs"
    const val Settings = "settings"
    const val Import = "import"
    const val ImportCustom = "import/custom"
    const val Create = "create"
    const val Detail = "pack/{packId}"
    const val Conversion = "conversion/{packId}"

    fun detail(packId: String) = "pack/$packId"
    fun conversion(packId: String) = "conversion/$packId"
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
fun StickersFtwApp(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val conversion by viewModel.conversion.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreview.collectAsStateWithLifecycle()
    val customSelection by viewModel.customSelection.collectAsStateWithLifecycle()
    val botUsername by viewModel.botUsername.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = mainDestinations.any { it.route == currentRoute }

    val whatsappAvailable = installedApps.whatsappInstalled || installedApps.whatsappBusinessInstalled
    val useBusinessWhatsapp = preferBusinessWhatsapp(installedApps)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                            colors = NavigationBarItemDefaults.colors(
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
                ConvertScreen(
                    settings = settings,
                    installedApps = installedApps,
                    packs = packs,
                    onOpenPack = { navController.navigate(Routes.detail(it)) },
                    onImportPack = { navController.navigate(Routes.Import) },
                    onCreatePack = { navController.navigate(Routes.Create) },
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
                        viewModel.requestPackUpdate(packId)
                        navController.navigate(Routes.conversion(packId))
                    },
                    onDisableUpdates = viewModel::disableUpdatesForPack,
                    contentPadding = scaffoldPadding,
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    settings = settings,
                    botUsername = botUsername,
                    onFetchBotUsername = viewModel::fetchBotUsername,
                    onSetServerUrl = viewModel::setServerUrl,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetTelegramUserId = viewModel::setTelegramUserId,
                    onSetUpdateChecksEnabled = viewModel::setUpdateChecksEnabled,
                    contentPadding = scaffoldPadding,
                )
            }
            composable(Routes.Import) {
                ImportPackScreen(
                    previewState = importPreview,
                    onLoadPreview = viewModel::loadPreview,
                    onResetPreview = viewModel::resetPreview,
                    onBack = { navController.popBackStack() },
                    onImport = { input, partIndex ->
                        val id = viewModel.startImport(input, partIndex)
                        navController.navigate(Routes.conversion(id)) {
                            popUpTo(Routes.Import) { inclusive = true }
                        }
                    },
                    onPickCustom = {
                        viewModel.beginCustomSelection()
                        navController.navigate(Routes.ImportCustom)
                    },
                    initialInput = viewModel.lastPreviewInput,
                )
            }
            composable(Routes.ImportCustom) {
                val loaded = importPreview as? ImportPreviewUiState.Loaded
                CustomStickerPickerScreen(
                    serverUrl = settings.serverUrl,
                    shortName = loaded?.shortName.orEmpty(),
                    stickers = loaded?.stickers.orEmpty(),
                    selectedIds = customSelection.orEmpty(),
                    onToggle = viewModel::toggleCustomSticker,
                    onSetAll = viewModel::setCustomSelectionAll,
                    onBack = { navController.popBackStack() },
                    onDownload = {
                        val id = viewModel.startImportCustom()
                        navController.navigate(Routes.conversion(id)) {
                            popUpTo(Routes.Import) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.Create) {
                CreatePackScreen(
                    onBack = { navController.popBackStack() },
                    onPublish = { items, title, shortName, pushToTelegram, addToWhatsapp ->
                        viewModel.createPack(items, title, shortName) { packId ->
                            viewModel.startPublish(packId, pushToTelegram, addToWhatsapp)
                            navController.navigate(Routes.conversion(packId)) {
                                popUpTo(Routes.Create) { inclusive = true }
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
                        viewModel.startPublish(packId, pushToTelegram = true, addToWhatsapp = false)
                        navController.navigate(Routes.conversion(packId))
                    },
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
                ConversionScreen(
                    pack = pack,
                    state = conversion,
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
                )
            }
        }
    }
}
