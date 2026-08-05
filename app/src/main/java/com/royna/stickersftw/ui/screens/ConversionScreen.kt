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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.AddToWhatsAppButton

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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                    hasError -> "Something went wrong"
                    state.isComplete -> "Pack ready"
                    else -> "Working on ${pack?.title.orEmpty()}"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    hasError -> state.errorMessage.orEmpty()
                    state.isComplete -> "The converted pack is ready."
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
                        InfoRow("Pack", pack.title)
                        InfoRow("Stickers", pack.stickerCount.toString())
                        InfoRow("Processing", "On this device")
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
                        Text("Back")
                    }
                }
                state.isComplete -> {
                    AddToWhatsAppButton(
                        enabled = true,
                        whatsappAvailable = whatsappAvailable,
                        onBuildIntent = onBuildWhatsappIntent,
                        onResult = onWhatsappResult,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenPacks,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open My Packs")
                    }
                }
                else -> {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Run in background")
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
