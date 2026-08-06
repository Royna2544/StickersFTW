package com.royna.stickersftw.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.royna.stickersftw.R
import com.royna.stickersftw.model.StickerGridItem
import com.royna.stickersftw.ui.components.StickerThumbnail
import kotlinx.coroutines.delay

private const val MIN_CELL_SIZE_DP = 64f
private const val MAX_CELL_SIZE_DP = 220f
private const val EMOJI_VISIBLE_MILLIS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerGridScreen(
    packTitle: String,
    stickers: List<StickerGridItem>,
    onBack: () -> Unit,
) {
    var cellSizeDp by remember { mutableFloatStateOf(110f) }
    var selected by remember { mutableStateOf<StickerGridItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(packTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (stickers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.pack_detail_no_stickers))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = cellSizeDp.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // Pinch to resize the grid cells -- there's nothing to
                    // pan/rotate here, only the zoom factor is used.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            cellSizeDp = (cellSizeDp * zoom).coerceIn(MIN_CELL_SIZE_DP, MAX_CELL_SIZE_DP)
                        }
                    },
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(stickers) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable(onClick = { selected = item }),
                    ) {
                        StickerThumbnail(item.path, modifier = Modifier.fillMaxSize().padding(6.dp))
                    }
                }
            }
        }
    }

    selected?.let { item ->
        EnlargedStickerDialog(item = item, onDismiss = { selected = null })
    }
}

@Composable
private fun EnlargedStickerDialog(item: StickerGridItem, onDismiss: () -> Unit) {
    var showEmoji by remember(item) { mutableStateOf(item.emoji.isNotBlank()) }
    LaunchedEffect(item) {
        showEmoji = item.emoji.isNotBlank()
        delay(EMOJI_VISIBLE_MILLIS)
        showEmoji = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(visible = showEmoji) {
                Text(
                    text = item.emoji.split(',').filter { it.isNotBlank() }.joinToString(" "),
                    fontSize = 40.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                StickerThumbnail(item.path, modifier = Modifier.fillMaxSize().padding(16.dp))
            }
        }
    }
}
