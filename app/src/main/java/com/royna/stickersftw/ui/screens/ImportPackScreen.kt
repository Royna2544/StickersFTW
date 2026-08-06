package com.royna.stickersftw.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
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
    initialInput: String = "",
) {
    var value by rememberSaveable { mutableStateOf(initialInput) }
    val normalized = value.trim()
    val context = LocalContext.current
    val enterPackLinkMessage = stringResource(R.string.err_enter_pack_link)
    // Blank input must never reach onImport -- it would navigate to the
    // conversion screen just to show a "no link entered" failure, when it
    // should instead be a no-op toast that keeps the user on this screen.
    val attemptImport: (Int) -> Unit = { part ->
        if (normalized.isBlank()) {
            Toast.makeText(context, enterPackLinkMessage, Toast.LENGTH_SHORT).show()
        } else {
            onImport(normalized, part)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_screen_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = stringResource(R.string.import_instructions),
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
                label = { Text(stringResource(R.string.import_field_label)) },
                placeholder = { Text(stringResource(R.string.import_field_placeholder)) },
                leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                singleLine = true,
            )
            Button(
                onClick = { onLoadPreview(normalized) },
                enabled = normalized.isNotBlank() && previewState !is ImportPreviewUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_load_preview))
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
                                text = pluralStringResource(
                                    R.plurals.stickers_count,
                                    previewState.totalStickerCount,
                                    previewState.totalStickerCount,
                                ),
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
                                    onClick = { attemptImport(0) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null)
                                    Text(stringResource(R.string.action_import_and_convert))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.import_choose_part),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                for (part in 0 until previewState.partCount) {
                                    Button(
                                        onClick = { attemptImport(part) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Rounded.Download, contentDescription = null)
                                        Text(stringResource(R.string.action_import_part, part + 1, previewState.partCount))
                                    }
                                }
                            }
                            TextButton(
                                onClick = onPickCustom,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.action_pick_own))
                            }
                        }
                    }
                }
            }
        }
    }
}
