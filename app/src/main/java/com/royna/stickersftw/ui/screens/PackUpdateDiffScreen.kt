package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.data.model.PackUpdateDiff
import com.royna.stickersftw.data.model.PackUpdateDiffResult
import com.royna.stickersftw.ui.theme.ErrorRed
import com.royna.stickersftw.ui.theme.PositiveGreen
import com.royna.stickersftw.ui.theme.appButtonColors

/** Shows what changed upstream before an update is accepted.
 *
 * Presented as a diff rather than a summary count because the decision it
 * supports is "is this worth several minutes of re-converting", and "12
 * stickers changed" doesn't answer that. Removals in red, additions in green,
 * an emoji edit as both.
 *
 * Accepting runs the update directly. There is no overwrite prompt: the whole
 * point of arriving here is that the user already looked at the change and
 * said yes, and asking again about the same pack would be noise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackUpdateDiffScreen(
    packTitle: String,
    state: PackUpdateDiffResult?,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.update_diff_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(packTitle, style = MaterialTheme.typography.headlineMedium)

            when (state) {
                is PackUpdateDiffResult.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                PackUpdateDiffResult.UpToDate -> Text(
                    text = stringResource(R.string.update_diff_up_to_date),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                PackUpdateDiffResult.NoBaseline -> Text(
                    text = stringResource(R.string.update_diff_no_baseline),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is PackUpdateDiffResult.Loaded -> DiffBody(state.diff)
            }

            Spacer(Modifier.height(6.dp))
            if (state !is PackUpdateDiffResult.UpToDate) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = appButtonColors(),
                ) {
                    Text(stringResource(R.string.action_update))
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DiffBody(diff: PackUpdateDiff) {
    Text(
        text = stringResource(R.string.update_diff_count, diff.countBefore, diff.countAfter),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (diff.titleChanged) {
        DiffSection(stringResource(R.string.update_diff_section_title)) {
            DiffLine("- ${diff.titleBefore}", removed = true)
            DiffLine("+ ${diff.titleAfter}", removed = false)
        }
    }

    if (diff.removed.isNotEmpty()) {
        DiffSection(stringResource(R.string.update_diff_section_removed, diff.removed.size)) {
            diff.removed.forEach { DiffLine("- ${it.emoji} ${it.id}", removed = true) }
        }
    }

    if (diff.added.isNotEmpty()) {
        DiffSection(stringResource(R.string.update_diff_section_added, diff.added.size)) {
            diff.added.forEach { DiffLine("+ ${it.emoji} ${it.id}", removed = false) }
        }
    }

    if (diff.emojiChanged.isNotEmpty()) {
        DiffSection(stringResource(R.string.update_diff_section_emoji, diff.emojiChanged.size)) {
            diff.emojiChanged.forEach { change ->
                DiffLine("- ${change.before} ${change.id}", removed = true)
                DiffLine("+ ${change.after} ${change.id}", removed = false)
            }
        }
    }

    if (diff.isReorderOnly) {
        Text(
            text = stringResource(R.string.update_diff_reorder_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiffSection(heading: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

/** Tinted background as well as tinted text: colour alone would be the only
 * thing distinguishing an addition from a removal, which fails for anyone who
 * can't separate red from green. The leading +/- carries the same meaning. */
@Composable
private fun DiffLine(text: String, removed: Boolean) {
    val accent = if (removed) ErrorRed else PositiveGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Surface(color = accent.copy(alpha = 0.10f), shape = RoundedCornerShape(6.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
