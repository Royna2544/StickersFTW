package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.InstalledAppsState
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
    onOpenPack: (String) -> Unit,
    onSeeAll: () -> Unit,
    contentPadding: PaddingValues,
) {
    val pinned = packs.filter { it.isPinned }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 158.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(
                title = stringResource(R.string.convert_title),
                subtitle = stringResource(R.string.app_tagline),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ServiceStatusPanel(
                serverUrl = settings.serverUrl,
                telegramClient = installedApps.telegramClient,
                whatsappInstalled = installedApps.whatsappInstalled || installedApps.whatsappBusinessInstalled,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = stringResource(R.string.section_pinned),
                action = stringResource(R.string.action_see_all),
                onAction = onSeeAll,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
