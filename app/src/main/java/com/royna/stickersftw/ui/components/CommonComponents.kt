package com.royna.stickersftw.ui.components

import android.app.Activity
import android.content.Intent
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.ServerConnectionStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramClientInfo
import com.royna.stickersftw.model.TelegramClientKind
import com.royna.stickersftw.ui.theme.PositiveGreen
import com.royna.stickersftw.ui.theme.UpdateAvailableYellow
import com.royna.stickersftw.ui.theme.TelegramBlue
import com.royna.stickersftw.ui.theme.WhatsAppGreen
import com.royna.stickersftw.ui.theme.appButtonColors
import java.io.File

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PackStatusChip(
    status: PackStatus,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (status) {
        PackStatus.Building -> stringResource(R.string.pack_status_building) to MaterialTheme.colorScheme.outline
        PackStatus.Downloading -> stringResource(R.string.pack_status_downloading) to TelegramBlue
        PackStatus.Converting -> stringResource(R.string.pack_status_converting) to TelegramBlue
        PackStatus.Ready -> stringResource(R.string.pack_status_ready) to PositiveGreen
        PackStatus.Failed -> stringResource(R.string.pack_status_failed) to MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun StickerThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
    fallbackEmoji: String = "✨",
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(fallbackEmoji, fontSize = 30.sp)
        }
    }
}

@Composable
fun StickerPreviewImagesRow(
    paths: List<String>,
    modifier: Modifier = Modifier,
    max: Int = 6,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.take(max).forEach { path ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(36.dp).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun TelegramClientInfo?.detailLabel(): String = when (this?.kind) {
    null -> stringResource(R.string.status_not_installed)
    TelegramClientKind.Official -> stringResource(R.string.status_installed)
    TelegramClientKind.OfficialAlt -> displayName
    TelegramClientKind.ThirdParty -> displayName
}

@Composable
private fun TelegramClientInfo?.statusLabel(): String = when (this?.kind) {
    null -> stringResource(R.string.status_dash)
    TelegramClientKind.Official, TelegramClientKind.OfficialAlt -> stringResource(R.string.status_ok)
    TelegramClientKind.ThirdParty -> stringResource(R.string.status_third_party)
}

@Composable
fun ServiceStatusPanel(
    serverUrl: String,
    serverStatus: ServerConnectionStatus,
    onRetryServerCheck: () -> Unit,
    telegramClient: TelegramClientInfo?,
    whatsappInstalled: Boolean,
    modifier: Modifier = Modifier,
) {
    val (serverStatusLabel, serverStatusColor) = when (serverStatus) {
        ServerConnectionStatus.Unknown -> stringResource(R.string.status_dash) to MaterialTheme.colorScheme.outline
        ServerConnectionStatus.Checking -> stringResource(R.string.status_server_checking) to MaterialTheme.colorScheme.outline
        ServerConnectionStatus.Connected -> stringResource(R.string.status_server_ready) to PositiveGreen
        ServerConnectionStatus.Failed -> stringResource(R.string.status_server_unreachable) to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        StatusRow(
            icon = Icons.Rounded.Cloud,
            title = stringResource(R.string.status_server_title),
            detail = serverUrl.removePrefix("http://").removePrefix("https://"),
            status = serverStatusLabel,
            statusColor = serverStatusColor,
            onClick = onRetryServerCheck,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
        StatusRow(
            icon = Icons.AutoMirrored.Rounded.Send,
            title = stringResource(R.string.status_telegram_title),
            detail = telegramClient.detailLabel(),
            status = telegramClient.statusLabel(),
            statusColor = if (telegramClient != null) TelegramBlue else MaterialTheme.colorScheme.outline,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
        StatusRow(
            icon = Icons.Rounded.Workspaces,
            title = stringResource(R.string.status_whatsapp_title),
            detail = stringResource(if (whatsappInstalled) R.string.status_installed else R.string.status_not_installed),
            status = stringResource(if (whatsappInstalled) R.string.status_ok else R.string.status_dash),
            statusColor = if (whatsappInstalled) WhatsAppGreen else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    status: String,
    statusColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(102.dp),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            color = statusColor.copy(alpha = 0.10f),
            contentColor = statusColor,
            shape = RoundedCornerShape(100.dp),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
fun PackGridCard(
    pack: StickerPack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(272.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StickerThumbnail(pack.trayIconPath, modifier = Modifier.size(56.dp).weight(1f, fill = false))
                PackStatusChip(pack.status)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(pack.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${pack.author} · " +
                        pluralStringResource(R.plurals.stickers_count, pack.stickerCount, pack.stickerCount),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                StickerPreviewImagesRow(pack.previewStickerPaths, max = 4)
            }
        }
    }
}

/** Speed-dial FAB replacing the old pinned Import/Create cards: collapsed,
 * it's a single "+" bubble; expanded, it becomes an "X" with two smaller
 * labeled bubbles (Import, Create) stacked above it. Shared between the
 * Convert and My Packs tabs. */
@Composable
fun ExpandableActionFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(horizontalAlignment = Alignment.End) {
                LabeledMiniFab(
                    label = stringResource(R.string.create_pack_label),
                    icon = Icons.Rounded.AddPhotoAlternate,
                    onClick = { onToggle(); onCreate() },
                )
                Spacer(Modifier.height(14.dp))
                LabeledMiniFab(
                    label = stringResource(R.string.import_pack_card_label),
                    icon = Icons.Rounded.Download,
                    onClick = { onToggle(); onImport() },
                )
                Spacer(Modifier.height(14.dp))
            }
        }
        androidx.compose.material3.FloatingActionButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.Close else Icons.Rounded.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.cd_collapse_actions else R.string.cd_expand_actions,
                ),
            )
        }
    }
}

@Composable
private fun LabeledMiniFab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.SmallFloatingActionButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = null)
        }
    }
}

@Composable
fun PackListCard(
    pack: StickerPack,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit,
    onRequestUpdate: () -> Unit = {},
    onDisableUpdates: () -> Unit = {},
    activeProgress: Float? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        CircleShape,
                    )
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                StickerThumbnail(pack.trayIconPath, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pack.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        // Two lines because the part number lives at the end
                        // of the title -- "(Part 2/4)" and the "(Animated)"
                        // suffix a split adds are exactly what a single line
                        // ellipsises away, leaving several rows that read
                        // identically.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pack.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.cd_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PackStatusChip(pack.status)
                    Text(
                        // Splitting a mixed pack leaves two rows whose titles
                        // differ only by a suffix the list truncates away, so
                        // the kind has to be visible here.
                        text = pluralStringResource(R.plurals.stickers_count, pack.stickerCount, pack.stickerCount) +
                            if (pack.isAnimated) stringResource(R.string.pack_detail_animated_suffix) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(7.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WhatsappFreshnessBadge(pack.whatsappFreshness)
                    TelegramFreshnessBadge(pack.telegramFreshness)
                }
                if (activeProgress != null) {
                    Spacer(Modifier.height(9.dp))
                    LinearProgressIndicator(
                        progress = { activeProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(9.dp))
                    StickerPreviewImagesRow(pack.previewStickerPaths, max = 6)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = pack.updatedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_pack_menu))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (pack.isPinned) R.string.action_unpin else R.string.action_pin)) },
                            onClick = {
                                menuExpanded = false
                                onTogglePinned()
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.PushPin, contentDescription = null)
                            },
                        )
                        if (pack.updateAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_review_update)) },
                                onClick = {
                                    menuExpanded = false
                                    onRequestUpdate()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Update,
                                        contentDescription = null,
                                        tint = UpdateAvailableYellow,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_disable_updates)) },
                                onClick = {
                                    menuExpanded = false
                                    onDisableUpdates()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

        if (pack.updateAvailable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(14.dp)
                    .background(UpdateAvailableYellow, CircleShape)
                    .clickable(onClick = onRequestUpdate),
            )
        }
    }

}

/** The shell shared by the app's blocking, full-screen questions.
 *
 * These take over the screen rather than sitting in a card because each one
 * is a decision the user cannot skip past. Going full screen means opting out
 * of the dialog window's inset fitting: left on, the window stops short of
 * the system bars and the scrim behind it shows through as grey bands above
 * and below the surface, which reads as a layout bug rather than a choice. */
@Composable
private fun FullScreenChoiceDialog(
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                actions()
            }
        }
    }
}

@Composable
fun MixedPackChoiceDialog(
    animatedCount: Int,
    staticCount: Int,
    onSplit: () -> Unit,
    onKeepTogether: () -> Unit,
) {
    FullScreenChoiceDialog(
        title = stringResource(R.string.mixed_pack_title),
        message = stringResource(R.string.mixed_pack_body, animatedCount, staticCount),
        // Not dismissible: a conversion is suspended waiting for the answer,
        // and there is no sensible default to pick on the user's behalf --
        // one choice costs an extra pack, the other costs the animation.
        onDismissRequest = {},
        dismissible = false,
    ) {
        Button(
            onClick = onSplit,
            colors = appButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_split_by_type))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onKeepTogether,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_keep_together))
        }
    }
}

@Composable
fun DuplicatePackOverwriteDialog(
    packTitle: String,
    onOverwrite: () -> Unit,
    onCancel: () -> Unit,
) {
    FullScreenChoiceDialog(
        title = stringResource(R.string.duplicate_pack_title),
        message = stringResource(R.string.duplicate_pack_message, packTitle),
        onDismissRequest = onCancel,
    ) {
        Button(
            onClick = onOverwrite,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_overwrite))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** Full-screen for the same reason as [DuplicatePackOverwriteDialog] --
 * this permanently deletes the Telegram sticker set (not just the local
 * copy), which cannot be undone. */
@Composable
fun DeleteTelegramPackConfirmDialog(
    packTitle: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    FullScreenChoiceDialog(
        title = stringResource(R.string.delete_telegram_pack_title),
        message = stringResource(R.string.delete_telegram_pack_message, packTitle),
        onDismissRequest = onCancel,
    ) {
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_delete_from_telegram_and_local))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** A neutral counterpart to [SuccessBadge], for stating a fact about a pack
 * rather than confirming something went well -- green would read as an
 * achievement where this is just a label. */
@Composable
fun NeutralBadge(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(100.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SuccessBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = PositiveGreen.copy(alpha = 0.12f),
        contentColor = PositiveGreen,
        shape = RoundedCornerShape(100.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Shared Add/refresh action for WhatsApp's ENABLE_STICKER_PACK dialog.
 * The displayed content revision and exact consumer/business target are
 * captured before launch. The owner uses a confirmed result for targeted
 * acknowledgement (which re-checks the real whitelist) and a cancelled result
 * for passive presence refresh. */
@Composable
fun AddToWhatsAppButton(
    enabled: Boolean,
    whatsappAvailable: Boolean,
    expectedRevision: Int,
    business: Boolean,
    onBuildIntent: () -> Intent?,
    onResult: (confirmed: Boolean, expectedRevision: Int, business: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes labelRes: Int = R.string.action_add_to_whatsapp,
) {
    var pendingRevision by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingBusiness by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val launchedRevision = pendingRevision
        val launchedBusiness = pendingBusiness
        pendingRevision = null
        pendingBusiness = null
        if (launchedRevision != null && launchedBusiness != null) {
            onResult(
                result.resultCode == Activity.RESULT_OK,
                launchedRevision,
                launchedBusiness,
            )
        }
    }

    Column(modifier = modifier) {
        Button(
            onClick = {
                onBuildIntent()?.let { intent ->
                    pendingRevision = expectedRevision
                    pendingBusiness = business
                    launcher.launch(intent)
                }
            },
            enabled = enabled && whatsappAvailable,
            colors = appButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Workspaces, contentDescription = null)
            Text(stringResource(labelRes))
        }
        if (!whatsappAvailable) {
            Text(
                text = stringResource(R.string.whatsapp_not_installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}
