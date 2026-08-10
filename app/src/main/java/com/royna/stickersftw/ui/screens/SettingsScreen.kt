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
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.BuildConfig
import com.royna.stickersftw.R
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.BackendMode
import com.royna.stickersftw.model.ThemeMode
import com.royna.stickersftw.ui.ServerUrlSaveResult
import com.royna.stickersftw.ui.components.PageHeader
import com.royna.stickersftw.ui.theme.appButtonColors

@Composable
fun SettingsScreen(
    settings: AppSettings,
    botUsername: String?,
    onFetchBotUsername: () -> Unit,
    onSetBackendMode: (BackendMode) -> Unit,
    onCheckAndSaveServerUrl: (url: String, onResult: (ServerUrlSaveResult) -> Unit) -> Unit,
    onForceSaveServerUrl: (String) -> Unit,
    onCheckAndSaveBotToken: (token: String, onResult: (ServerUrlSaveResult) -> Unit) -> Unit,
    onForceSaveBotToken: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetTelegramUserId: (String) -> Unit,
    onSetUpdateChecksEnabled: (Boolean) -> Unit,
    onSetPingTestsEnabled: (Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    var editingServer by remember { mutableStateOf(false) }
    var checkingServer by remember { mutableStateOf(false) }
    var pendingFailedUrl by remember { mutableStateOf<String?>(null) }
    var editingBotToken by remember { mutableStateOf(false) }
    var checkingBotToken by remember { mutableStateOf(false) }
    var pendingFailedBotToken by remember { mutableStateOf<String?>(null) }
    var telegramUserId by remember(settings.telegramUserId) { mutableStateOf(settings.telegramUserId) }

    LaunchedEffect(settings.backendMode, settings.serverUrl, settings.botToken) {
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
            title = stringResource(R.string.settings_title),
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
        )

        Text(
            text = stringResource(R.string.settings_section_backend),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackendModeChoice(
                mode = BackendMode.ServerUrl,
                selected = settings.backendMode == BackendMode.ServerUrl,
                onClick = { onSetBackendMode(BackendMode.ServerUrl) },
                modifier = Modifier.weight(1f),
            )
            BackendModeChoice(
                mode = BackendMode.BotToken,
                selected = settings.backendMode == BackendMode.BotToken,
                onClick = { onSetBackendMode(BackendMode.BotToken) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))
        if (settings.backendMode == BackendMode.ServerUrl) {
            Text(
                text = stringResource(R.string.settings_section_server_url),
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
                        text = stringResource(R.string.action_edit),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_section_bot_token),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingBotToken = true },
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
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = if (settings.botToken.isBlank()) {
                            stringResource(R.string.bot_token_row_placeholder)
                        } else {
                            stringResource(R.string.bot_token_row_masked)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_edit),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.settings_section_telegram_push),
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
                val botLabel = botUsername?.let { "@$it" } ?: stringResource(R.string.settings_telegram_push_bot_fallback)
                Text(
                    text = stringResource(R.string.settings_telegram_push_body, botLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = telegramUserId,
                    onValueChange = { telegramUserId = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_telegram_user_id_label)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                    singleLine = true,
                )
                Button(
                    onClick = { onSetTelegramUserId(telegramUserId) },
                    enabled = telegramUserId != settings.telegramUserId,
                    colors = appButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.settings_section_pack_updates),
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
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_pack_updates_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = settings.updateChecksEnabled,
                    onCheckedChange = onSetUpdateChecksEnabled,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.settings_section_connection),
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
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_ping_tests_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = settings.pingTestsEnabled,
                    onCheckedChange = onSetPingTestsEnabled,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.settings_section_theme),
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
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_description),
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
            isChecking = checkingServer,
            onDismiss = { editingServer = false },
            onSave = { value ->
                checkingServer = true
                onCheckAndSaveServerUrl(value) { result ->
                    checkingServer = false
                    when (result) {
                        ServerUrlSaveResult.Saved -> editingServer = false
                        ServerUrlSaveResult.ConnectionFailed -> pendingFailedUrl = value
                    }
                }
            },
        )
    }

    val failedUrl = pendingFailedUrl
    if (failedUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingFailedUrl = null },
            title = { Text(stringResource(R.string.server_url_unreachable_title)) },
            text = { Text(stringResource(R.string.server_url_unreachable_message, failedUrl)) },
            confirmButton = {
                Button(
                    onClick = {
                        onForceSaveServerUrl(failedUrl)
                        pendingFailedUrl = null
                        editingServer = false
                    },
                ) { Text(stringResource(R.string.action_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFailedUrl = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (editingBotToken) {
        BotTokenDialog(
            initialValue = settings.botToken,
            isChecking = checkingBotToken,
            onDismiss = { editingBotToken = false },
            onSave = { value ->
                checkingBotToken = true
                onCheckAndSaveBotToken(value) { result ->
                    checkingBotToken = false
                    when (result) {
                        ServerUrlSaveResult.Saved -> editingBotToken = false
                        ServerUrlSaveResult.ConnectionFailed -> pendingFailedBotToken = value
                    }
                }
            },
        )
    }

    val failedBotToken = pendingFailedBotToken
    if (failedBotToken != null) {
        AlertDialog(
            onDismissRequest = { pendingFailedBotToken = null },
            title = { Text(stringResource(R.string.bot_token_unreachable_title)) },
            text = { Text(stringResource(R.string.bot_token_unreachable_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        onForceSaveBotToken(failedBotToken)
                        pendingFailedBotToken = null
                        editingBotToken = false
                    },
                ) { Text(stringResource(R.string.action_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFailedBotToken = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun BackendModeChoice(
    mode: BackendMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when (mode) {
        BackendMode.ServerUrl -> Icons.Rounded.Language
        BackendMode.BotToken -> Icons.Rounded.Key
    }
    val label = stringResource(
        when (mode) {
            BackendMode.ServerUrl -> R.string.backend_mode_server_url
            BackendMode.BotToken -> R.string.backend_mode_bot_token
        },
    )

    Card(
        modifier = modifier
            .height(90.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
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
    val label = stringResource(
        when (mode) {
            ThemeMode.System -> R.string.theme_system
            ThemeMode.Light -> R.string.theme_light
            ThemeMode.Dark -> R.string.theme_dark
        },
    )

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
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ServerUrlDialog(
    initialValue: String,
    isChecking: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val valid = value.startsWith("http://") || value.startsWith("https://")

    AlertDialog(
        onDismissRequest = { if (!isChecking) onDismiss() },
        title = { Text(stringResource(R.string.server_url_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.server_url_dialog_body))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = !isChecking,
                    singleLine = true,
                    label = { Text(stringResource(R.string.server_url_dialog_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (valid) R.string.server_url_dialog_example else R.string.server_url_dialog_hint_invalid,
                            ),
                        )
                    },
                )
                if (isChecking) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.server_url_dialog_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }, enabled = valid && !isChecking, colors = appButtonColors()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isChecking) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Loose sanity check (digits, colon, then the auth-string segment) --
 * intentionally not a strict Telegram token grammar validator, just enough
 * to catch an obviously wrong paste before it's sent to Telegram. */
private val BOT_TOKEN_REGEX = Regex("^\\d+:[A-Za-z0-9_-]{30,}$")

@Composable
private fun BotTokenDialog(
    initialValue: String,
    isChecking: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var revealed by remember { mutableStateOf(false) }
    val valid = BOT_TOKEN_REGEX.matches(value.trim())

    AlertDialog(
        onDismissRequest = { if (!isChecking) onDismiss() },
        title = { Text(stringResource(R.string.bot_token_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bot_token_dialog_body))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    enabled = !isChecking,
                    singleLine = true,
                    label = { Text(stringResource(R.string.bot_token_dialog_label)) },
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (revealed) R.string.cd_hide_token else R.string.cd_show_token,
                                ),
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                if (valid) R.string.bot_token_dialog_example else R.string.bot_token_dialog_hint_invalid,
                            ),
                        )
                    },
                )
                if (isChecking) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.bot_token_dialog_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value.trim()) }, enabled = valid && !isChecking, colors = appButtonColors()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isChecking) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
