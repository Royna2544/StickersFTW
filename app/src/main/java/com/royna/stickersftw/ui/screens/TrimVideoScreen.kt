package com.royna.stickersftw.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.ui.theme.appButtonColors
import kotlinx.coroutines.delay

/** Chooses which stretch of a long clip becomes the sticker.
 *
 * A sticker is capped at [SizeBudget.MAX_TOTAL_DURATION_MS], and the frames
 * worth keeping are almost never the opening ones -- a clip that runs a minute
 * used to be silently truncated to its first few seconds. The window length is
 * fixed, so there is exactly one thing to choose: where it starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimVideoScreen(
    durationMs: Long,
    startMs: Long,
    previewFrame: Bitmap?,
    position: Int = 1,
    total: Int = 1,
    onStartChanged: (Long) -> Unit,
    onConfirm: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val windowMs = SizeBudget.MAX_TOTAL_DURATION_MS
    val maxStart = (durationMs - windowMs).coerceAtLeast(0L)
    // Scrubbing fires a decode per position, so the preview follows the slider
    // only once it settles. Without this a drag queues a decode per pixel.
    // position is the identity of the clip in this batch. A plain remember
    // carries the previous clip's chosen position into the next trim screen.
    var scrubbing by remember(position) { mutableStateOf(startMs.toFloat()) }
    LaunchedEffect(scrubbing, position) {
        delay(120)
        onStartChanged(scrubbing.toLong())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (total > 1) {
                            stringResource(R.string.trim_title_of, position, total)
                        } else {
                            stringResource(R.string.trim_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    R.string.trim_body,
                    formatSeconds(windowMs),
                    formatSeconds(durationMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (previewFrame != null) {
                    Image(
                        bitmap = previewFrame.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(18.dp))
            Slider(
                value = scrubbing,
                onValueChange = { scrubbing = it },
                valueRange = 0f..maxStart.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatSeconds(scrubbing.toLong()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = formatSeconds((scrubbing.toLong() + windowMs).coerceAtMost(durationMs)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { onConfirm(scrubbing.toLong()) },
                colors = appButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.trim_confirm))
            }
        }
    }
}

private fun formatSeconds(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
