package com.royna.stickersftw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R

/**
 * Names the independent local copy created before editing a linked pack.
 * The caller owns the fork and action-replay work; this component only
 * returns a trimmed, nonblank title or cancellation.
 */
@Composable
fun RemixPackDialog(
    packTitle: String,
    isCreating: Boolean = false,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var remixTitle by rememberSaveable(packTitle) {
        mutableStateOf("$packTitle (Remix)")
    }
    val normalizedTitle = remixTitle.trim()
    val isValid = normalizedTitle.isNotEmpty()
    val keyboardController = LocalSoftwareKeyboardController.current

    fun confirm() {
        if (!isValid) return
        keyboardController?.hide()
        onConfirm(normalizedTitle)
    }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onCancel() },
        title = { Text(stringResource(R.string.remix_pack_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.remix_pack_message))
                if (isCreating) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.remix_pack_creating))
                }
                OutlinedTextField(
                    value = remixTitle,
                    onValueChange = { remixTitle = it },
                    enabled = !isCreating,
                    label = { Text(stringResource(R.string.remix_pack_name_label)) },
                    supportingText = if (!isValid) {
                        { Text(stringResource(R.string.remix_pack_name_required)) }
                    } else {
                        null
                    },
                    isError = !isValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::confirm,
                enabled = isValid && !isCreating,
            ) {
                Text(stringResource(R.string.remix_pack_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isCreating) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
