package com.royna.stickersftw.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.MediaCrop
import com.royna.stickersftw.ui.theme.appButtonColors
import kotlin.math.max
import kotlin.math.roundToInt

/** Non-destructive square crop editor shared by still images and videos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropMediaScreen(
    previewFrame: Bitmap?,
    position: Int = 1,
    total: Int = 1,
    onConfirm: (MediaCrop) -> Unit,
    onKeepFull: () -> Unit,
    onBack: () -> Unit,
) {
    var zoom by remember(position) { mutableFloatStateOf(1f) }
    var offset by remember(position) { mutableStateOf(Offset.Zero) }
    var viewport by remember(position) { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, atZoom: Float): Offset {
        val bitmap = previewFrame ?: return Offset.Zero
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val baseScale = max(
            viewport.width.toFloat() / bitmap.width,
            viewport.height.toFloat() / bitmap.height,
        )
        val maxX = ((bitmap.width * baseScale * atZoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((bitmap.height * baseScale * atZoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun selectedCrop(): MediaCrop {
        val bitmap = requireNotNull(previewFrame)
        val baseScale = max(
            viewport.width.toFloat() / bitmap.width,
            viewport.height.toFloat() / bitmap.height,
        )
        val totalScale = baseScale * zoom
        val cropWidth = viewport.width / totalScale
        val cropHeight = viewport.height / totalScale
        val centerX = bitmap.width / 2f - offset.x / totalScale
        val centerY = bitmap.height / 2f - offset.y / totalScale
        return MediaCrop(
            left = ((centerX - cropWidth / 2f) / bitmap.width).coerceIn(0f, 1f),
            top = ((centerY - cropHeight / 2f) / bitmap.height).coerceIn(0f, 1f),
            right = ((centerX + cropWidth / 2f) / bitmap.width).coerceIn(0f, 1f),
            bottom = ((centerY + cropHeight / 2f) / bitmap.height).coerceIn(0f, 1f),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (total > 1) {
                            stringResource(R.string.crop_title_of, position, total)
                        } else {
                            stringResource(R.string.crop_title)
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
                text = stringResource(R.string.crop_body),
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
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (previewFrame == null) {
                    CircularProgressIndicator()
                } else {
                    val image = previewFrame.asImageBitmap()
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged {
                                viewport = it
                                offset = clampOffset(offset, zoom)
                            }
                            .pointerInput(previewFrame, position) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val nextZoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                                    val zoomRatio = nextZoom / zoom
                                    offset = clampOffset(offset * zoomRatio + pan, nextZoom)
                                    zoom = nextZoom
                                }
                            },
                    ) {
                        val baseScale = max(size.width / image.width, size.height / image.height)
                        val totalScale = baseScale * zoom
                        val destinationSize = IntSize(
                            (image.width * totalScale).roundToInt(),
                            (image.height * totalScale).roundToInt(),
                        )
                        val destinationOffset = IntOffset(
                            ((size.width - destinationSize.width) / 2f + offset.x).roundToInt(),
                            ((size.height - destinationSize.height) / 2f + offset.y).roundToInt(),
                        )
                        drawImage(
                            image = image,
                            dstOffset = destinationOffset,
                            dstSize = destinationSize,
                            filterQuality = FilterQuality.High,
                        )
                        val gridColor = Color.White.copy(alpha = 0.65f)
                        drawRect(gridColor, style = Stroke(width = 2.dp.toPx()))
                        drawLine(gridColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height))
                        drawLine(gridColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height))
                        drawLine(gridColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f))
                        drawLine(gridColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.crop_zoom),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = zoom,
                onValueChange = { next ->
                    val ratio = next / zoom
                    offset = clampOffset(offset * ratio, next)
                    zoom = next
                },
                valueRange = 1f..MAX_ZOOM,
                enabled = previewFrame != null,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onKeepFull, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.crop_keep_full))
                }
                Button(
                    onClick = { onConfirm(selectedCrop()) },
                    enabled = previewFrame != null && viewport.width > 0,
                    colors = appButtonColors(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.crop_confirm))
                }
            }
        }
    }
}

private const val MAX_ZOOM = 5f
