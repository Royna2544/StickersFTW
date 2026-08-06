package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.ui.ImportPreviewUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPackScreen(
    previewState: ImportPreviewUiState,
    onLoadPreview: (String) -> Unit,
    onResetPreview: () -> Unit,
    onBack: () -> Unit,
    onImport: (String, Int) -> Unit,
    onPickCustom: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val normalized = value.trim()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Telegram Pack") },
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
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Paste a Telegram sticker link or enter its short name.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    onResetPreview()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Telegram pack") },
                placeholder = { Text("https://t.me/addstickers/UtyaDuck") },
                leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                singleLine = true,
            )
            Button(
                onClick = { onLoadPreview(normalized) },
                enabled = normalized.isNotBlank() && previewState !is ImportPreviewUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load preview")
            }

            when (previewState) {
                is ImportPreviewUiState.Idle -> Unit
                is ImportPreviewUiState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ImportPreviewUiState.Error -> {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = previewState.message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                is ImportPreviewUiState.Loaded -> {
                    Card(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = previewState.title,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                text = "${previewState.totalStickerCount} stickers",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (previewState.emojis.isNotEmpty()) {
                                Text(previewState.emojis.joinToString(" "), style = MaterialTheme.typography.titleLarge)
                            }
                            if (previewState.warning != null) {
                                Text(
                                    text = previewState.warning,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            if (previewState.partCount <= 1) {
                                Button(
                                    onClick = { onImport(normalized, 0) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null)
                                    Text("  Import and convert")
                                }
                            } else {
                                Text(
                                    text = "Choose a part to import:",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                for (part in 0 until previewState.partCount) {
                                    Button(
                                        onClick = { onImport(normalized, part) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Rounded.Download, contentDescription = null)
                                        Text("  Import Part ${part + 1} of ${previewState.partCount}")
                                    }
                                }
                            }
                            TextButton(
                                onClick = onPickCustom,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("I want to pick my own")
                            }
                        }
                    }
                }
            }
        }
    }
}
