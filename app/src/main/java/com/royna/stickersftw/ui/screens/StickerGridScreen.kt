package com.royna.stickersftw.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.model.StickerGridItem
import com.royna.stickersftw.model.parseStickerEmojis
import com.royna.stickersftw.ui.components.StickerThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.pow

private const val MIN_CELL_SIZE_DP = 64f
private const val MAX_CELL_SIZE_DP = 220f
private const val MIN_PACK_SIZE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerGridScreen(
    packTitle: String,
    stickers: List<StickerGridItem>,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit = {},
    onReplace: (Long, PickedMediaItem) -> Unit = { _, _ -> },
    onUpdateEmoji: suspend (Long, String) -> Boolean = { _, _ -> false },
    onSetTray: suspend (Long) -> Boolean = { false },
    onDelete: suspend (Long) -> Boolean = { false },
    onReorder: suspend (List<Long>) -> Boolean = { false },
    onUndo: suspend () -> Unit = {},
    onFinalizeUndo: suspend () -> Unit = {},
    isBusy: Boolean = false,
    busyStage: String = "",
    busyProgress: Float = 0f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val ordered = remember { mutableStateListOf<StickerGridItem>() }
    var cellSizeDp by remember { mutableFloatStateOf(110f) }
    var selectedRowId by remember { mutableStateOf<Long?>(null) }
    var pendingReplaceRowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var draggingRowId by remember { mutableStateOf<Long?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var dragTouchOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOriginal by remember { mutableStateOf<List<StickerGridItem>?>(null) }
    var undoPending by remember { mutableStateOf(false) }
    var mutationJob by remember { mutableStateOf<Job?>(null) }
    val latestUndoPending by rememberUpdatedState(undoPending)
    val latestFinalizeUndo by rememberUpdatedState(onFinalizeUndo)
    val latestMutationJob by rememberUpdatedState(mutationJob)

    // Usually Back or the Snackbar resolves this snapshot. This fallback also
    // covers a parent navigation/state change removing the destination while
    // the Snackbar is visible. The short-lived scope deliberately outlives
    // this composition just long enough to release retained delete files.
    DisposableEffect(Unit) {
        onDispose {
            if (latestUndoPending) {
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                    latestMutationJob?.join()
                    latestFinalizeUndo()
                }
            }
        }
    }

    fun replaceLocal(items: List<StickerGridItem>) {
        ordered.clear()
        ordered.addAll(items)
    }

    LaunchedEffect(stickers, draggingRowId, undoPending) {
        if (draggingRowId == null && !undoPending) {
            replaceLocal(stickers.sortedBy(StickerGridItem::position))
        }
    }
    LaunchedEffect(isBusy) {
        if (isBusy) selectedRowId = null
    }

    val undoLabel = stringResource(R.string.action_undo)
    val reorderMessage = stringResource(R.string.sticker_editor_reordered)
    val deleteMessage = stringResource(R.string.sticker_editor_deleted)
    val failureMessage = stringResource(R.string.sticker_editor_save_failed)
    val moveEarlierLabel = stringResource(R.string.sticker_editor_move_earlier)
    val moveLaterLabel = stringResource(R.string.sticker_editor_move_later)
    val stickerDescription = stringResource(R.string.sticker_editor_sticker)

    suspend fun offerUndo(message: String, original: List<StickerGridItem>) {
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        // Back navigation may already have finalized the pending snapshot and
        // dismissed this Snackbar. In that case there is nothing left to do.
        if (!undoPending) return
        if (result == SnackbarResult.ActionPerformed) {
            onUndo()
            replaceLocal(original)
        } else {
            onFinalizeUndo()
        }
        undoPending = false
    }

    fun leaveScreen() {
        if (mutationJob?.isActive == true) return
        if (!undoPending) {
            onBack()
            return
        }
        // Repository implementations retain deleted files/order snapshots
        // until this signal. Navigating away counts as declining Undo.
        undoPending = false
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            onFinalizeUndo()
            onBack()
        }
    }

    BackHandler(enabled = isBusy || undoPending || mutationJob?.isActive == true) {
        if (!isBusy && mutationJob?.isActive != true) leaveScreen()
    }

    fun commitReorder(original: List<StickerGridItem>) {
        val before = original.map(StickerGridItem::rowId)
        val after = ordered.map(StickerGridItem::rowId)
        if (before == after) return
        undoPending = true
        mutationJob = scope.launch {
            if (onReorder(after)) {
                mutationJob = null
                offerUndo(reorderMessage, original)
            } else {
                replaceLocal(original)
                undoPending = false
                mutationJob = null
                snackbarHostState.showSnackbar(failureMessage)
            }
        }
    }

    val replaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val rowId = pendingReplaceRowId
        pendingReplaceRowId = null
        if (uri != null && rowId != null) {
            val mimeType = context.contentResolver.getType(uri)
            val kind = if (mimeType?.startsWith("video/") == true) {
                PickedMediaKind.Video
            } else {
                PickedMediaKind.Image
            }
            onReplace(rowId, PickedMediaItem(uri = uri.toString(), kind = kind))
        }
    }

    val interactionsEnabled = !isBusy && !undoPending
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (isBusy) Modifier.clearAndSetSemantics { } else Modifier,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(packTitle) },
                    navigationIcon = {
                        IconButton(
                            onClick = ::leaveScreen,
                            enabled = !isBusy && mutationJob?.isActive != true,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            if (ordered.isEmpty()) {
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
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // Only claim a gesture after a second pointer appears.
                        // Single-pointer scroll, tap, and long-press drag stay
                        // available to the grid and its children.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        cellSizeDp = (cellSizeDp * zoom)
                                            .coerceIn(MIN_CELL_SIZE_DP, MAX_CELL_SIZE_DP)
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = ordered,
                        key = StickerGridItem::rowId,
                    ) { item ->
                        val itemIndex = ordered.indexOfFirst { it.rowId == item.rowId }
                        val isDragging = draggingRowId == item.rowId
                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == item.rowId }
                        val translation = if (isDragging && itemInfo != null) {
                            Offset(
                                x = dragPointer.x - itemInfo.offset.x - dragTouchOffset.x,
                                y = dragPointer.y - itemInfo.offset.y - dragTouchOffset.y,
                            )
                        } else {
                            Offset.Zero
                        }
                        val accessibilityActions = buildList {
                            if (itemIndex > 0) {
                                add(
                                    CustomAccessibilityAction(moveEarlierLabel) {
                                        if (!interactionsEnabled) return@CustomAccessibilityAction false
                                        val currentIndex = ordered.indexOfFirst { it.rowId == item.rowId }
                                        if (currentIndex <= 0) return@CustomAccessibilityAction false
                                        val original = ordered.toList()
                                        ordered.move(currentIndex, currentIndex - 1)
                                        commitReorder(original)
                                        true
                                    },
                                )
                            }
                            if (itemIndex in 0 until ordered.lastIndex) {
                                add(
                                    CustomAccessibilityAction(moveLaterLabel) {
                                        if (!interactionsEnabled) return@CustomAccessibilityAction false
                                        val currentIndex = ordered.indexOfFirst { it.rowId == item.rowId }
                                        if (currentIndex !in 0 until ordered.lastIndex) {
                                            return@CustomAccessibilityAction false
                                        }
                                        val original = ordered.toList()
                                        ordered.move(currentIndex, currentIndex + 1)
                                        commitReorder(original)
                                        true
                                    },
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .animateItem()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationX = translation.x
                                    translationY = translation.y
                                    if (isDragging) {
                                        scaleX = 1.04f
                                        scaleY = 1.04f
                                        shadowElevation = 12.dp.toPx()
                                    }
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "$stickerDescription ${itemIndex + 1}"
                                    customActions = accessibilityActions
                                }
                                .pointerInput(item.rowId, interactionsEnabled) {
                                    if (!interactionsEnabled) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { touchOffset ->
                                            val info = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == item.rowId }
                                                ?: return@detectDragGesturesAfterLongPress
                                            dragOriginal = ordered.toList()
                                            draggingRowId = item.rowId
                                            dragTouchOffset = touchOffset
                                            dragPointer = Offset(
                                                info.offset.x + touchOffset.x,
                                                info.offset.y + touchOffset.y,
                                            )
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragPointer += amount
                                            val visible = gridState.layoutInfo.visibleItemsInfo
                                            val target = visible.minByOrNull { info ->
                                                val centerX = info.offset.x + info.size.width / 2f
                                                val centerY = info.offset.y + info.size.height / 2f
                                                (centerX - dragPointer.x).pow(2) +
                                                    (centerY - dragPointer.y).pow(2)
                                            }
                                            val from = ordered.indexOfFirst { it.rowId == item.rowId }
                                            val to = target?.index ?: from
                                            if (from >= 0 && to in ordered.indices && from != to) {
                                                ordered.move(from, to)
                                            }

                                            val edge = 72.dp.toPx()
                                            val viewportEnd = gridState.layoutInfo.viewportEndOffset.toFloat()
                                            val scroll = when {
                                                dragPointer.y < edge -> -18f
                                                dragPointer.y > viewportEnd - edge -> 18f
                                                else -> 0f
                                            }
                                            if (scroll != 0f) {
                                                scope.launch { gridState.scrollBy(scroll) }
                                            }
                                        },
                                        onDragCancel = {
                                            dragOriginal?.let(::replaceLocal)
                                            dragOriginal = null
                                            draggingRowId = null
                                        },
                                        onDragEnd = {
                                            val original = dragOriginal
                                            dragOriginal = null
                                            draggingRowId = null
                                            if (original != null) commitReorder(original)
                                        },
                                    )
                                }
                                .clickable(
                                    enabled = interactionsEnabled,
                                    onClick = { selectedRowId = item.rowId },
                                ),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .aspectRatio(1f),
                            ) {
                                StickerThumbnail(
                                    item.path,
                                    modifier = Modifier.fillMaxSize().padding(6.dp),
                                )
                            }
                            if (item.isTray) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Star,
                                        contentDescription = stringResource(R.string.sticker_editor_tray),
                                        modifier = Modifier.padding(5.dp).size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val selected = selectedRowId?.let { rowId -> ordered.firstOrNull { it.rowId == rowId } }
        if (selected != null && !isBusy) {
            StickerEditorSheet(
                item = selected,
                canDelete = ordered.size > MIN_PACK_SIZE && !undoPending,
                actionsEnabled = !undoPending,
                onDismiss = { selectedRowId = null },
                onEdit = {
                    selectedRowId = null
                    onEdit(selected.rowId)
                },
                onReplace = {
                    pendingReplaceRowId = selected.rowId
                    selectedRowId = null
                    replaceLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                onSaveEmoji = { emoji ->
                    val success = onUpdateEmoji(selected.rowId, emoji)
                    if (success) {
                        val index = ordered.indexOfFirst { it.rowId == selected.rowId }
                        if (index >= 0) ordered[index] = ordered[index].copy(emoji = emoji)
                    }
                    success
                },
                onSetTray = {
                    val success = onSetTray(selected.rowId)
                    if (success) {
                        ordered.indices.forEach { index ->
                            ordered[index] = ordered[index].copy(
                                isTray = ordered[index].rowId == selected.rowId,
                            )
                        }
                    }
                    success
                },
                onDelete = {
                    val runningJob = currentCoroutineContext()[Job]
                    mutationJob = runningJob
                    val original = ordered.toList()
                    undoPending = true
                    try {
                        val success = onDelete(selected.rowId)
                        if (success) {
                            ordered.removeAll { it.rowId == selected.rowId }
                            selectedRowId = null
                            scope.launch { offerUndo(deleteMessage, original) }
                        } else {
                            undoPending = false
                            snackbarHostState.showSnackbar(failureMessage)
                        }
                        success
                    } finally {
                        if (mutationJob === runningJob) mutationJob = null
                    }
                },
            )
        }

        if (isBusy) {
            BusyEditorOverlay(stage = busyStage, progress = busyProgress)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerEditorSheet(
    item: StickerGridItem,
    canDelete: Boolean,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onReplace: () -> Unit,
    onSaveEmoji: suspend (String) -> Boolean,
    onSetTray: suspend () -> Boolean,
    onDelete: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var emojiInput by remember(item.rowId) {
        mutableStateOf(item.emoji.split(',').filter(String::isNotBlank).joinToString(" "))
    }
    var isSaving by remember(item.rowId) { mutableStateOf(false) }
    var saveFailed by remember(item.rowId) { mutableStateOf(false) }
    val normalizedEmoji = normalizeEmoji(emojiInput)

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetGesturesEnabled = !isSaving,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    StickerThumbnail(item.path, modifier = Modifier.fillMaxSize().padding(14.dp))
                }
                if (item.isTray) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text(stringResource(R.string.sticker_editor_tray), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = emojiInput,
                onValueChange = {
                    emojiInput = it
                    saveFailed = false
                },
                enabled = actionsEnabled && !isSaving,
                label = { Text(stringResource(R.string.sticker_editor_emoji_label)) },
                supportingText = {
                    Text(
                        when {
                            saveFailed -> stringResource(R.string.sticker_editor_save_failed)
                            normalizedEmoji == null -> stringResource(R.string.sticker_editor_emoji_error)
                            else -> stringResource(R.string.sticker_editor_emoji_hint)
                        },
                    )
                },
                isError = saveFailed || normalizedEmoji == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = actionsEnabled && !isSaving && normalizedEmoji != null,
                onClick = {
                    val value = normalizedEmoji ?: return@Button
                    scope.launch {
                        isSaving = true
                        saveFailed = !onSaveEmoji(value)
                        isSaving = false
                        if (!saveFailed) onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sticker_editor_save_emoji))
            }

            HorizontalDivider()
            FilledTonalButton(
                onClick = onEdit,
                enabled = actionsEnabled && !isSaving && item.canEditVisual,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sticker_editor_edit_visual))
            }
            if (!item.canEditVisual) {
                Text(
                    stringResource(R.string.sticker_editor_visual_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onReplace,
                enabled = actionsEnabled && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sticker_editor_replace))
            }
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveFailed = !onSetTray()
                        isSaving = false
                        if (!saveFailed) onDismiss()
                    }
                },
                enabled = actionsEnabled && !isSaving && !item.isTray,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (item.isTray) {
                        stringResource(R.string.sticker_editor_current_tray)
                    } else {
                        stringResource(R.string.sticker_editor_set_tray)
                    },
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveFailed = !onDelete()
                        isSaving = false
                    }
                },
                enabled = actionsEnabled && !isSaving && canDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.action_delete),
                    color = if (canDelete) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
            if (!canDelete) {
                Text(
                    stringResource(R.string.sticker_editor_delete_minimum),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BusyEditorOverlay(stage: String, progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (progress > 0f) {
                    CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
                    Text(
                        stringResource(
                            R.string.sticker_editor_progress,
                            (progress.coerceIn(0f, 1f) * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                } else {
                    CircularProgressIndicator()
                }
                Text(
                    stage.ifBlank { stringResource(R.string.sticker_editor_working) },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

private fun normalizeEmoji(value: String): String? {
    return parseStickerEmojis(value)?.joinToString(",")
}

private fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to) return
    add(to, removeAt(from))
}
