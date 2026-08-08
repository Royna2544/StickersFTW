package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.royna.stickersftw.R
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.data.model.PreviewSticker

/** A gallery-style checkable grid of a pack's sticker thumbnails, letting
 * the user hand-pick an arbitrary <=30-sticker subset instead of a
 * contiguous auto-split part. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomStickerPickerScreen(
    thumbnailUrls: Map<String, String>,
    stickers: List<PreviewSticker>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    val allSelected = stickers.isNotEmpty() && selectedIds.size == stickers.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    TextButton(onClick = { onSetAll(!allSelected) }) {
                        Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all))
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selectedIds.size}/${stickers.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = onDownload,
                        enabled = selectedIds.size in SizeBudget.MIN_STICKERS..SizeBudget.MAX_STICKERS,
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Text(stringResource(R.string.action_download))
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(stickers, key = { it.id }) { sticker ->
                val isSelected = sticker.id in selectedIds
                val thumbnailUrl = thumbnailUrls[sticker.id]
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .clickable { onToggle(sticker.id) },
                ) {
                    if (thumbnailUrl == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        SubcomposeAsyncImage(
                            model = thumbnailUrl,
                            contentDescription = sticker.emoji,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            },
                        )
                    }
                    if (!isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                        )
                    }
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = stringResource(if (isSelected) R.string.cd_selected else R.string.cd_not_selected),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .background(
                                Color.White.copy(alpha = if (isSelected) 1f else 0.3f),
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
