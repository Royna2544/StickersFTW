package com.royna.stickersftw.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.conversion.VideoStickerConverter
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the trim screen is currently asking about. */
data class TrimRequest(
    /** 1-based, for "clip 2 of 3" when a batch has several long videos. */
    val position: Int,
    val total: Int,
    val durationMs: Long,
    val startMs: Long,
    val preview: Bitmap?,
)

/** Runs picked media past the trim screen before it becomes stickers.
 *
 * First the clip is copied out of its content:// URI into a file this app
 * owns, because every decode below needs a real path and the URI's grant is
 * not guaranteed to outlive the picker. Then anything longer than a sticker
 * is allowed to be gets queued for the user to place its window.
 *
 * Kept out of the ViewModel because it is a small state machine in its own
 * right, and because the alternative was a fifth pile of nullable fields in
 * there.
 */
class MediaTrimCoordinator(private val context: Context) {
    private val _request = MutableStateFlow<TrimRequest?>(null)
    val request: StateFlow<TrimRequest?> = _request.asStateFlow()

    private var queue = emptyList<Int>()
    private var queueIndex = 0
    private var items = emptyList<PickedMediaItem>()
    private var onResolved: ((List<PickedMediaItem>) -> Unit)? = null
    /** Invalidates a preview decode whenever the active clip changes. */
    private var requestToken = 0L

    /** Starts the edit flow and calls [onReady] once every requested trim
     * decision has been made. */
    suspend fun begin(
        picked: List<PickedMediaItem>,
        onReady: (List<PickedMediaItem>) -> Unit,
    ): Boolean {
        // A new picker/share result supersedes any trim flow that was still
        // open. It also makes a preview already decoding for that flow stale.
        clear()
        val materialised = withContext(Dispatchers.IO) { picked.map { materialise(it) } }
        val durations = withContext(Dispatchers.IO) {
            materialised.map { item ->
                if (item.kind != PickedMediaKind.Video) {
                    null
                } else {
                    fileOf(item)?.let { VideoStickerConverter.durationMsOf(it) }
                }
            }
        }

        items = materialised
        queue = durations.indices.filter { index ->
            (durations[index] ?: 0L) > SizeBudget.MAX_TOTAL_DURATION_MS
        }
        queueIndex = 0

        if (queue.isEmpty()) {
            onReady(materialised)
            return false
        }

        onResolved = onReady
        durationsByIndex = durations
        showCurrent()
        return true
    }

    private var durationsByIndex: List<Long?> = emptyList()

    suspend fun setStart(startMs: Long) {
        val current = _request.value ?: return
        val token = requestToken
        val itemIndex = queue[queueIndex]
        val clampedStart = clampStart(startMs, current.durationMs)
        _request.value = current.copy(startMs = clampedStart, preview = null)
        updateItemStart(itemIndex, clampedStart)
        val frame = previewFrame(items[itemIndex], clampedStart)
        // A decode can finish after another scrub, after Confirm advanced to
        // the next clip, or after a new batch replaced this one. Only the
        // request that started it may receive the bitmap.
        if (requestToken == token && _request.value?.startMs == clampedStart) {
            _request.value = _request.value?.copy(preview = frame)
        }
    }

    suspend fun confirm(startMs: Long) {
        val current = _request.value ?: return
        val itemIndex = queue[queueIndex]
        // Confirm receives the slider's value directly. That makes a quick
        // scrub-and-confirm deterministic even if the debounced preview has
        // not started (or finished) yet.
        updateItemStart(itemIndex, clampStart(startMs, current.durationMs))
        queueIndex++
        if (queueIndex < queue.size) {
            showCurrent()
        } else {
            val resolved = items
            val callback = onResolved
            clear()
            callback?.invoke(resolved)
        }
    }

    fun cancel() {
        clear()
    }

    private fun clear() {
        requestToken++
        _request.value = null
        onResolved = null
        queue = emptyList()
        queueIndex = 0
        items = emptyList()
        durationsByIndex = emptyList()
    }

    private suspend fun showCurrent() {
        val itemIndex = queue[queueIndex]
        val duration = durationsByIndex.getOrNull(itemIndex) ?: 0L
        val token = ++requestToken
        val startMs = clampStart(items[itemIndex].trimStartMs, duration)
        updateItemStart(itemIndex, startMs)
        _request.value = TrimRequest(
            position = queueIndex + 1,
            total = queue.size,
            durationMs = duration,
            startMs = startMs,
            preview = null,
        )
        val frame = previewFrame(items[itemIndex], startMs)
        if (requestToken == token && _request.value?.startMs == startMs) {
            _request.value = _request.value?.copy(preview = frame)
        }
    }

    private suspend fun previewFrame(item: PickedMediaItem, startMs: Long): Bitmap? {
        val file = fileOf(item) ?: return null
        // Asked for over the sticker's own window rather than a short one, so
        // what is shown is literally the first frame of the sticker that would
        // come out. A narrow window looks like it should be cheaper, but it
        // cannot be trusted to contain a frame at all: sparse video encodes
        // only what changes, and a clip that sits still can go seconds between
        // frames -- asked for 1ms, then 1s, this preview came back empty both
        // times on a screen recording with three frames in 27 seconds. The
        // decode runs from the preceding keyframe regardless, so the wider
        // window costs little beyond what was already unavoidable.
        return withContext(Dispatchers.Default) {
            VideoStickerConverter
                .extractFrames(
                    file,
                    SizeBudget.STICKER_PX,
                    maxDurationMs = SizeBudget.MAX_TOTAL_DURATION_MS,
                    startMs = startMs,
                )
                ?.firstOrNull()
                ?.bitmap
        }
    }

    private fun clampStart(startMs: Long, durationMs: Long): Long =
        startMs.coerceIn(
            0L,
            (durationMs - SizeBudget.MAX_TOTAL_DURATION_MS).coerceAtLeast(0L),
        )

    private fun updateItemStart(itemIndex: Int, startMs: Long) {
        items = items.toMutableList().also {
            it[itemIndex] = it[itemIndex].copy(trimStartMs = startMs)
        }
    }

    /** Copies a picked item into app storage, unless it is already a file
     * this app owns (a share has been copied once already). */
    private fun materialise(item: PickedMediaItem): PickedMediaItem {
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "file") return item
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val destination = File(directory, UUID.randomUUID().toString())
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return item
            item.copy(uri = Uri.fromFile(destination).toString())
        } catch (_: Exception) {
            destination.delete()
            item
        }
    }

    private fun fileOf(item: PickedMediaItem): File? =
        Uri.parse(item.uri).path?.let(::File)?.takeIf { it.exists() }

    private companion object {
        const val DIRECTORY = "picked"
    }
}
