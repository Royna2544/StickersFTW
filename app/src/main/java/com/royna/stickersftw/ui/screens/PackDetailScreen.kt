package com.royna.stickersftw.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramPushState
import com.royna.stickersftw.ui.components.AddToWhatsAppButton
import com.royna.stickersftw.ui.components.DeleteTelegramPackConfirmDialog
import com.royna.stickersftw.ui.components.PackStatusChip
import com.royna.stickersftw.ui.components.StickerThumbnail
import com.royna.stickersftw.ui.components.SuccessBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    pack: StickerPack?,
    whatsappAvailable: Boolean,
    onBack: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBuildWhatsappIntent: () -> Intent?,
    onWhatsappResult: () -> Unit,
    onRefreshWhatsapp: (String) -> Unit,
    onPushToTelegram: (String) -> Unit,
    onDeleteFromTelegram: (packId: String, onDone: (success: Boolean, error: String?) -> Unit) -> Unit = { _, _ -> },
    onForceRefreshFromTelegram: (packId: String, onDone: (message: String) -> Unit) -> Unit = { _, _ -> },
    onViewAllStickers: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showDeleteTelegramConfirm by remember { mutableStateOf(false) }
    var deletingFromTelegram by remember { mutableStateOf(false) }
    var isForceRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(pack?.id) {
        pack?.let { onRefreshWhatsapp(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pack_detail_view_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (pack != null) {
                        if (pack.origin == PackOrigin.Imported) {
                            IconButton(
                                onClick = {
                                    isForceRefreshing = true
                                    onForceRefreshFromTelegram(pack.id) { message ->
                                        isForceRefreshing = false
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isForceRefreshing,
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_force_refresh))
                            }
                        }
                        IconButton(onClick = { onTogglePinned(pack.id) }) {
                            Icon(Icons.Rounded.PushPin, contentDescription = stringResource(R.string.cd_pin_pack))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (pack == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.pack_detail_not_found))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PackHeroCard(pack)

            Text(stringResource(R.string.pack_detail_preview), style = MaterialTheme.typography.titleLarge)
            if (pack.previewStickerPaths.isEmpty()) {
                Text(
                    text = stringResource(R.string.pack_detail_no_stickers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pack.previewStickerPaths) { path ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            StickerThumbnail(path, modifier = Modifier.size(48.dp))
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onViewAllStickers(pack.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.GridView, contentDescription = null)
                    Text(stringResource(R.string.action_view_all_stickers))
                }
            }

            if (pack.status == PackStatus.Ready) {
                AddToWhatsAppButton(
                    enabled = true,
                    whatsappAvailable = whatsappAvailable,
                    onBuildIntent = onBuildWhatsappIntent,
                    onResult = onWhatsappResult,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val telegramPushState = pack.telegramPushState
            if (pack.origin == PackOrigin.Created && telegramPushState !is TelegramPushState.Pushed) {
                androidx.compose.material3.Button(
                    onClick = { onPushToTelegram(pack.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Text(
                        if (telegramPushState is TelegramPushState.Partial) {
                            stringResource(R.string.action_finish_push_to_telegram)
                        } else {
                            stringResource(R.string.action_push_to_telegram)
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = { onDelete(pack.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Text(stringResource(R.string.action_delete_local_pack))
            }

            if (pack.origin == PackOrigin.Created && telegramPushState !is TelegramPushState.NotPushed) {
                OutlinedButton(
                    onClick = { showDeleteTelegramConfirm = true },
                    enabled = !deletingFromTelegram,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Text(stringResource(R.string.action_delete_from_telegram_and_local))
                }
            }
        }
    }

    if (showDeleteTelegramConfirm && pack != null) {
        DeleteTelegramPackConfirmDialog(
            packTitle = pack.title,
            onConfirm = {
                showDeleteTelegramConfirm = false
                deletingFromTelegram = true
                onDeleteFromTelegram(pack.id) { success, error ->
                    deletingFromTelegram = false
                    if (success) {
                        onBack()
                    } else {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onCancel = { showDeleteTelegramConfirm = false },
        )
    }
}

@Composable
private fun PackHeroCard(pack: StickerPack) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ) {
                StickerThumbnail(pack.trayIconPath, modifier = Modifier.size(100.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(pack.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                pack.author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PackStatusChip(pack.status)
                if (pack.whatsappAdded == true) SuccessBadge(stringResource(R.string.badge_added_to_whatsapp))
                when (val telegramState = pack.telegramPushState) {
                    is TelegramPushState.Pushed -> SuccessBadge(stringResource(R.string.badge_on_telegram))
                    is TelegramPushState.Partial -> SuccessBadge(
                        stringResource(R.string.badge_on_telegram_partial, telegramState.pushedCount, telegramState.totalCount),
                    )
                    is TelegramPushState.NotPushed -> Unit
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = pluralStringResource(R.plurals.stickers_count, pack.stickerCount, pack.stickerCount) +
                    if (pack.isAnimated) stringResource(R.string.pack_detail_animated_suffix) else "",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (pack.errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pack.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (pack.warningMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pack.warningMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
