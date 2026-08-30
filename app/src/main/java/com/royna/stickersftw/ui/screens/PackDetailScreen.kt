package com.royna.stickersftw.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.ui.components.AddStickerSource
import com.royna.stickersftw.ui.components.AddStickerSourceSheet
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramFreshnessState
import com.royna.stickersftw.model.WhatsappFreshnessState
import com.royna.stickersftw.ui.components.AddToWhatsAppButton
import com.royna.stickersftw.ui.components.DeleteTelegramPackConfirmDialog
import com.royna.stickersftw.ui.components.NeutralBadge
import com.royna.stickersftw.ui.components.PackStatusChip
import com.royna.stickersftw.ui.components.StickerThumbnail
import com.royna.stickersftw.ui.components.TelegramFreshnessBadge
import com.royna.stickersftw.ui.components.WhatsappFreshnessBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    pack: StickerPack?,
    whatsappAvailable: Boolean,
    whatsappBusiness: Boolean,
    onBack: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBuildWhatsappIntent: suspend () -> Intent?,
    onWhatsappResult: (confirmed: Boolean, expectedRevision: Int, business: Boolean) -> Unit,
    onRefreshWhatsapp: (String) -> Unit,
    onPushToTelegram: (String) -> Unit,
    onDeleteFromTelegram: (packId: String, onDone: (success: Boolean, error: String?) -> Unit) -> Unit = { _, _ -> },
    onForceRefreshFromTelegram: (packId: String, onDone: (message: String) -> Unit) -> Unit = { _, _ -> },
    onViewAllStickers: (String) -> Unit = {},
    onAddStickers: (packId: String, items: List<PickedMediaItem>) -> Unit = { _, _ -> },
    reconversionCheckInProgress: Boolean = false,
    onReconvert: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showDeleteTelegramConfirm by remember { mutableStateOf(false) }
    var deletingFromTelegram by remember { mutableStateOf(false) }
    var isForceRefreshing by remember { mutableStateOf(false) }
    var showAddSourceSheet by remember { mutableStateOf(false) }

    // Derived from the pack's own count, which is what actually converted --
    // see StickerPackRepository.remainingCapacity for why failed rows must not
    // be counted here.
    val remaining = ((pack?.stickerCount ?: 0).let { SizeBudget.MAX_STICKERS - it }).coerceAtLeast(0)

    fun deliver(uris: List<Uri>) {
        val packId = pack?.id ?: return
        if (uris.isEmpty()) return
        onAddStickers(
            packId,
            uris.map { uri ->
                val mimeType = context.contentResolver.getType(uri)
                PickedMediaItem(
                    uri = uri.toString(),
                    kind = if (mimeType?.startsWith("video/") == true) {
                        PickedMediaKind.Video
                    } else {
                        PickedMediaKind.Image
                    },
                )
            },
        )
    }

    // Two contracts because PickMultipleVisualMedia rejects a maxItems of 1,
    // which is exactly the case when a pack has one slot left. The picker is
    // capped at what will fit so the limit is enforced where the user is
    // choosing, rather than as a rejection after the fact.
    val pickMultiple = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(remaining.coerceAtLeast(2)),
    ) { uris -> deliver(uris) }
    val pickSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> deliver(listOfNotNull(uri)) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PackHeroCard(pack)

            if (pack.needsReconversion) {
                OutlinedButton(
                    onClick = { onReconvert(pack.id) },
                    enabled = !reconversionCheckInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (reconversionCheckInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.reconversion_checking_telegram))
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_reconvert_pack))
                    }
                }
            }

            Text(stringResource(R.string.pack_detail_preview), style = MaterialTheme.typography.titleLarge)
            if (pack.previewStickerPaths.isEmpty()) {
                Text(
                    text = stringResource(R.string.pack_detail_no_stickers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StickerPreviewGrid(pack.previewStickerPaths)
                OutlinedButton(
                    onClick = { onViewAllStickers(pack.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.GridView, contentDescription = null)
                    Text(stringResource(R.string.action_view_all_stickers))
                }
            }

            if (pack.status == PackStatus.Ready) {
                OutlinedButton(
                    onClick = { showAddSourceSheet = true },
                    enabled = remaining > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                    Text(stringResource(R.string.action_add_stickers))
                }
                if (remaining == 0) {
                    Text(
                        text = stringResource(R.string.pack_detail_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (pack.status == PackStatus.Ready) {
                when (pack.whatsappFreshness) {
                    WhatsappFreshnessState.NotAdded,
                    WhatsappFreshnessState.NeedsRefresh -> AddToWhatsAppButton(
                        enabled = true,
                        whatsappAvailable = whatsappAvailable,
                        expectedRevision = pack.imageDataVersion,
                        business = whatsappBusiness,
                        onBuildIntent = onBuildWhatsappIntent,
                        onResult = onWhatsappResult,
                        modifier = Modifier.fillMaxWidth(),
                        labelRes = if (pack.whatsappFreshness == WhatsappFreshnessState.NeedsRefresh) {
                            R.string.action_refresh_whatsapp_pack
                        } else {
                            R.string.action_add_to_whatsapp
                        },
                    )
                    WhatsappFreshnessState.Current -> Unit
                }
            }

            val telegramFreshness = pack.telegramFreshness
            if (pack.origin == PackOrigin.Created) {
                when (telegramFreshness) {
                    TelegramFreshnessState.NotPushed,
                    TelegramFreshnessState.Partial -> androidx.compose.material3.Button(
                        onClick = { onPushToTelegram(pack.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Text(
                            stringResource(
                                if (telegramFreshness == TelegramFreshnessState.Partial) {
                                    R.string.action_finish_push_to_telegram
                                } else {
                                    R.string.action_push_to_telegram
                                },
                            ),
                        )
                    }
                    // This release reports drift but deliberately offers no
                    // push/update action for an existing Telegram set.
                    TelegramFreshnessState.Current,
                    TelegramFreshnessState.OutOfDate -> Unit
                }
            }

            OutlinedButton(
                onClick = { onDelete(pack.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Text(stringResource(R.string.action_delete_local_pack))
            }

            if (pack.origin == PackOrigin.Created && telegramFreshness != TelegramFreshnessState.NotPushed) {
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
            // Keeps the last button off the bottom edge now that the column
            // scrolls rather than being clipped to one screen.
            Spacer(Modifier.height(4.dp))
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

    if (showAddSourceSheet && pack != null) {
        AddStickerSourceSheet(
            remaining = remaining,
            onPick = { source ->
                showAddSourceSheet = false
                when (source) {
                    AddStickerSource.DeviceMedia -> {
                        val request = PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                        )
                        if (remaining == 1) pickSingle.launch(request) else pickMultiple.launch(request)
                    }
                }
            },
            onDismiss = { showAddSourceSheet = false },
        )
    }
}

/** The preview teaser under the hero card.
 *
 * Rows are laid out directly rather than through a LazyVerticalGrid: the list
 * is a bounded teaser, so laziness bought nothing and cost a hardcoded height
 * that never matched the content. Cells take an equal share of the width and
 * an [aspectRatio] of 1, which is what makes them square at any screen size --
 * the previous fixed 48.dp thumbnail left the rounded background stretched
 * around it wherever the column was wider than that. A short final row is
 * padded with spacers so its cells stay the same size as a full row's. */
@Composable
private fun StickerPreviewGrid(
    paths: List<String>,
    columns: Int = 6,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { path ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        StickerThumbnail(
                            path,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                        )
                    }
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
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
        // fillMaxWidth is what actually centres this block. Card's content
        // slot is a ColumnScope that aligns children to the start, so a
        // wrap-content column hugs the card's left edge and
        // CenterHorizontally only centres the children against each other.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(100.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ) {
                // Not the tray icon: that's a single flattened frame, because
                // WhatsApp requires a static 96px tray image (see
                // StickerConversionPipeline.buildTrayIcon), which left this
                // the one frozen thumbnail on an animated pack's screen. The
                // first converted sticker is the same artwork, animated.
                StickerThumbnail(
                    pack.previewStickerPaths.firstOrNull() ?: pack.trayIconPath,
                    modifier = Modifier.size(100.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                pack.title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                pack.author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            // A row, not a column: these are peer badges and reading as a
            // stack made the card taller than it needed to be. FlowRow so a
            // pack carrying all of them still wraps instead of overflowing.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PackStatusChip(pack.status)
                pack.conversionBias?.let { bias ->
                    NeutralBadge(
                        text = stringResource(
                            when (bias) {
                                ConversionBias.Sharpness -> R.string.conversion_bias_sharpness
                                ConversionBias.Auto -> R.string.conversion_bias_auto
                                ConversionBias.Smoothness -> R.string.conversion_bias_smoothness
                            },
                        ),
                        icon = Icons.Rounded.Tune,
                    )
                }
                WhatsappFreshnessBadge(pack.whatsappFreshness)
                TelegramFreshnessBadge(pack.telegramFreshness)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = pluralStringResource(R.plurals.stickers_count, pack.stickerCount, pack.stickerCount) +
                    if (pack.isAnimated) stringResource(R.string.pack_detail_animated_suffix) else "",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (pack.origin == PackOrigin.Imported) {
                val convertedVersionName = pack.convertedAppVersionName
                val convertedVersion: String = when {
                    !convertedVersionName.isNullOrBlank() -> convertedVersionName
                    pack.convertedAppVersionCode != null -> stringResource(
                        R.string.pack_detail_converted_app_build,
                        pack.convertedAppVersionCode,
                    )
                    else -> stringResource(R.string.pack_detail_converted_app_version_unknown)
                }
                Text(
                    text = stringResource(R.string.pack_detail_converted_app_version, convertedVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (pack.errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pack.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else if (pack.warningMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pack.warningMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
