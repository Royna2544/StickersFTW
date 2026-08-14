package com.royna.stickersftw.ui.screens

import androidx.annotation.OptIn
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import com.royna.stickersftw.R
import com.royna.stickersftw.ui.VideoRange
import com.royna.stickersftw.ui.adjustVideoRange
import com.royna.stickersftw.ui.theme.appButtonColors
import java.util.Locale
import kotlinx.coroutines.delay

/** Chooses the exact source range used to build a video sticker. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimVideoScreen(
    mediaUri: String,
    durationMs: Long,
    startMs: Long,
    selectedDurationMs: Long,
    position: Int = 1,
    total: Int = 1,
    onRangeChanged: (startMs: Long, durationMs: Long) -> Unit,
    onConfirm: (startMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose(player::release)
    }

    var selectedStart by rememberSaveable(mediaUri, position) { mutableLongStateOf(startMs) }
    var selectedEnd by rememberSaveable(mediaUri, position) {
        mutableLongStateOf(startMs + selectedDurationMs)
    }
    var playheadMs by remember(mediaUri) { mutableLongStateOf(selectedStart) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val selection = VideoRange(selectedStart, selectedEnd - selectedStart)
    // Rebuilding a clipped MediaItem for every pixel of a drag makes the
    // decoder thrash. Let the handles settle briefly, then loop the exact
    // chosen section. Confirm still receives the undebounced values below.
    LaunchedEffect(player, mediaUri, selection) {
        delay(120)
        onRangeChanged(selection.startMs, selection.durationMs)
        val item = MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(selection.startMs)
                    .setEndPositionMs(selection.endMs)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        while (true) {
            playheadMs = selection.startMs +
                player.currentPosition.coerceIn(0L, selection.durationMs)
            delay(100)
        }
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
                text = stringResource(R.string.trim_body, formatTime(durationMs)),
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
                PlayerSurface(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                )
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING) {
                    CircularProgressIndicator()
                }
                Text(
                    text = stringResource(R.string.trim_preview_time, formatTime(playheadMs)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(18.dp))
            RangeSlider(
                value = selectedStart.toFloat()..selectedEnd.toFloat(),
                onValueChange = { requested ->
                    val adjusted = adjustVideoRange(
                        current = selection,
                        requestedStartMs = requested.start.toLong(),
                        requestedEndMs = requested.endInclusive.toLong(),
                        sourceDurationMs = durationMs,
                    )
                    selectedStart = adjusted.startMs
                    selectedEnd = adjusted.endMs
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                enabled = durationMs > 500L,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime(selectedStart),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = formatTime(selectedEnd),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(
                    R.string.trim_selected_duration,
                    formatTime(selectedEnd - selectedStart),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { onConfirm(selectedStart, selectedEnd - selectedStart) },
                colors = appButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.trim_confirm))
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalTenths = millis.coerceAtLeast(0L) / 100L
    val minutes = totalTenths / 600L
    val seconds = (totalTenths % 600L) / 10f
    return if (minutes == 0L) {
        String.format(Locale.getDefault(), "%.1fs", seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%04.1f", minutes, seconds)
    }
}
