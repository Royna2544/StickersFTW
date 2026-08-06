package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.InstalledAppsState
import com.royna.stickersftw.model.ServerConnectionStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.PackGridCard
import com.royna.stickersftw.ui.components.PageHeader
import com.royna.stickersftw.ui.components.SectionHeader
import com.royna.stickersftw.ui.components.ServiceStatusPanel

@Composable
fun ConvertScreen(
    settings: AppSettings,
    installedApps: InstalledAppsState,
    packs: List<StickerPack>,
    serverStatus: ServerConnectionStatus,
    onCheckServerConnection: () -> Unit,
    onOpenPack: (String) -> Unit,
    onSeeAll: () -> Unit,
    contentPadding: PaddingValues,
) {
    val pinned = packs.filter { it.isPinned }

    LaunchedEffect(settings.serverUrl, settings.pingTestsEnabled) {
        onCheckServerConnection()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            PageHeader(
                title = stringResource(R.string.convert_title),
                subtitle = stringResource(R.string.app_tagline),
            )
            Spacer(Modifier.height(18.dp))
            ServiceStatusPanel(
                serverUrl = settings.serverUrl,
                serverStatus = serverStatus,
                onRetryServerCheck = onCheckServerConnection,
                telegramClient = installedApps.telegramClient,
                whatsappInstalled = installedApps.whatsappInstalled || installedApps.whatsappBusinessInstalled,
            )
            Spacer(Modifier.height(18.dp))
            SectionHeader(
                title = stringResource(R.string.section_pinned),
                action = stringResource(R.string.action_see_all),
                onAction = onSeeAll,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (pinned.isEmpty()) {
                Text(
                    text = stringResource(R.string.section_pinned_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 158.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(
                        items = pinned,
                        key = { it.id },
                    ) { pack ->
                        PackGridCard(
                            pack = pack,
                            onClick = { onOpenPack(pack.id) },
                        )
                    }
                }
            }
        }
    }
}
