package com.royna.stickersftw.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.AddToWhatsAppButton
import kotlinx.coroutines.delay

/** m:ss -- these run into minutes for a video pack, and an hours component
 * would be claiming a range the size caps make impossible. */
private fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
    pack: StickerPack?,
    state: ConversionUiState,
    whatsappAvailable: Boolean,
    onBack: () -> Unit,
    onOpenPacks: () -> Unit,
    onBuildWhatsappIntent: () -> Intent?,
    onWhatsappResult: () -> Unit,
    splitPack: StickerPack? = null,
    onBuildSplitWhatsappIntent: () -> Intent? = { null },
    showConvertOtherParts: Boolean = false,
    onConvertOtherParts: () -> Unit = {},
    onRunInBackground: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversion_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hasError = state.errorMessage != null

            // Ticks once a second while the operation runs, then re-runs once
            // on the transition to finished so the figure that stays on screen
            // is the real total rather than whatever the last tick caught.
            val elapsedMs by produceState(0L, state.startedAtMillis, state.isRunning) {
                if (state.startedAtMillis <= 0L) {
                    value = 0L
                    return@produceState
                }
                while (true) {
                    value = System.currentTimeMillis() - state.startedAtMillis
                    if (!state.isRunning) break
                    delay(1_000)
                }
            }

            Surface(
                modifier = Modifier.size(104.dp),
                shape = CircleShape,
                color = if (hasError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = when {
                            hasError -> Icons.Rounded.ErrorOutline
                            state.isComplete -> Icons.Rounded.Check
                            else -> Icons.Rounded.HourglassTop
                        },
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                        tint = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = when {
                    hasError -> stringResource(R.string.conversion_error_title)
                    state.isComplete -> stringResource(R.string.conversion_ready_title)
                    else -> stringResource(R.string.conversion_working_on, pack?.title.orEmpty())
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    hasError -> state.errorMessage.orEmpty()
                    state.isComplete -> stringResource(R.string.conversion_ready_body)
                    else -> state.stage
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!hasError) {
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isSlowFormat && !state.isComplete && !hasError) {
                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.conversion_slow_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.conversion_slow_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (pack != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoRow(stringResource(R.string.conversion_pack_label), pack.title)
                        InfoRow(stringResource(R.string.conversion_stickers_label), pack.stickerCount.toString())
                        InfoRow(stringResource(R.string.conversion_processing_label), stringResource(R.string.conversion_processing_value))
                        if (state.startedAtMillis > 0L) {
                            InfoRow(stringResource(R.string.conversion_elapsed_label), formatElapsed(elapsedMs))
                        }
                        if (splitPack != null) {
                            InfoRow(
                                stringResource(R.string.conversion_split_label),
                                pluralStringResource(
                                    R.plurals.stickers_count,
                                    splitPack.stickerCount,
                                    splitPack.stickerCount,
                                ),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            when {
                hasError -> {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_back))
                    }
                }
                state.isComplete -> {
                    // Keeping a mixed pack together costs the smaller group
                    // its animation. Say so here, where the choice was just
                    // made, rather than only on the pack's detail screen --
                    // which the user has no particular reason to open.
                    pack?.warningMessage?.let { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    // Two packs came out of one import, so one "Add to
                    // WhatsApp" would quietly ship half the stickers.
                    if (splitPack != null) {
                        Text(
                            text = stringResource(R.string.conversion_split_body, splitPack.title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = pack?.title.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    AddToWhatsAppButton(
                        enabled = true,
                        whatsappAvailable = whatsappAvailable,
                        onBuildIntent = onBuildWhatsappIntent,
                        onResult = onWhatsappResult,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (splitPack != null) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = splitPack.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        AddToWhatsAppButton(
                            enabled = true,
                            whatsappAvailable = whatsappAvailable,
                            onBuildIntent = onBuildSplitWhatsappIntent,
                            onResult = onWhatsappResult,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (showConvertOtherParts) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onConvertOtherParts,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.action_convert_other_parts))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenPacks,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_open_my_packs))
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = {
                            onRunInBackground()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_run_in_background))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
