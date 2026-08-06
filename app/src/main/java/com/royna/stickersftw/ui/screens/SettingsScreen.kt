package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.BuildConfig
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.ThemeMode
import com.royna.stickersftw.ui.components.PageHeader

@Composable
fun SettingsScreen(
    settings: AppSettings,
    botUsername: String?,
    onFetchBotUsername: () -> Unit,
    onSetServerUrl: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetTelegramUserId: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var editingServer by remember { mutableStateOf(false) }
    var telegramUserId by remember(settings.telegramUserId) { mutableStateOf(settings.telegramUserId) }

    LaunchedEffect(settings.serverUrl) {
        onFetchBotUsername()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        PageHeader(
            title = "Settings",
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
        )

        Text(
            text = "SERVER URL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingServer = true },
            shape = RoundedCornerShape(25.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = settings.serverUrl,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                )
                Text(
                    text = "Edit",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "TELEGRAM PUSH",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val botLabel = botUsername?.let { "@$it" } ?: "the bot configured on your server"
                Text(
                    text = "To push a pack to Telegram, message $botLabel and send /start, " +
                        "then paste your numeric user ID below (get it from @userinfobot).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = telegramUserId,
                    onValueChange = { telegramUserId = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telegram user ID") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                    singleLine = true,
                )
                Button(
                    onClick = { onSetTelegramUserId(telegramUserId) },
                    enabled = telegramUserId != settings.telegramUserId,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save")
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "THEME",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeChoice(
                    mode = mode,
                    selected = settings.themeMode == mode,
                    onClick = { onSetThemeMode(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(34.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Stickers FTW v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "For The Win · For Telegram WhatsApp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Import an existing Telegram pack and add it to WhatsApp, or build a " +
                        "new pack from photos/video and publish it to Telegram and WhatsApp. " +
                        "Conversion runs locally on the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editingServer) {
        ServerUrlDialog(
            initialValue = settings.serverUrl,
            onDismiss = { editingServer = false },
            onSave = { value ->
                onSetServerUrl(value)
                editingServer = false
            },
        )
    }
}

@Composable
private fun ThemeChoice(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (mode) {
        ThemeMode.System -> Icons.Rounded.BrightnessAuto
        ThemeMode.Light -> Icons.Rounded.LightMode
        ThemeMode.Dark -> Icons.Rounded.DarkMode
    }

    Card(
        modifier = modifier
            .height(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(31.dp))
            Spacer(Modifier.height(12.dp))
            Text(mode.name, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ServerUrlDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val valid = value.startsWith("http://") || value.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server URL") },
        text = {
            Column {
                Text("The Android app uses this endpoint to fetch Telegram sticker files.")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Base URL") },
                    supportingText = {
                        Text(if (valid) "Example: http://10.0.2.2:8080" else "Include http:// or https://")
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }, enabled = valid) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
