package com.royna.stickersftw.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.royna.stickersftw.conversion.VideoStickerConverter
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.model.MediaCrop
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the video range screen is currently asking about. */
data class TrimRequest(
    /** 1-based, for "clip 2 of 3" when a batch has several videos. */
    val position: Int,
    val total: Int,
    /** App-owned file URI, safe for Media3 to keep open while this dialog lives. */
    val mediaUri: String,
    val durationMs: Long,
    val startMs: Long,
    val selectedDurationMs: Long,
)

/** What the crop editor is currently asking about. */
data class CropRequest(
    val position: Int,
    val total: Int,
    val preview: Bitmap?,
    val crop: MediaCrop?,
)

/** Runs picked media past the trim and crop screens before it becomes stickers.
 *
 * Three jobs, all of which have to happen before stickers can be created.
 * First the clip is copied out of its content:// URI into a file this app
 * owns, because preview and conversion need a stable source after the picker
 * grant expires. Every video with a readable duration is then queued for an
 * exact range choice. Finally, every usable image/video gets a
 * non-destructive crop choice.
 *
 * Kept out of the ViewModel because it is a small state machine in its own
 * right, and because the alternative was a fifth pile of nullable fields in
 * there.
 */
class MediaTrimCoordinator(private val context: Context) {
    private val _request = MutableStateFlow<TrimRequest?>(null)
    val request: StateFlow<TrimRequest?> = _request.asStateFlow()
    private val _cropRequest = MutableStateFlow<CropRequest?>(null)
    val cropRequest: StateFlow<CropRequest?> = _cropRequest.asStateFlow()

    private var queue = emptyList<Int>()
    private var queueIndex = 0
    private var cropIndex = 0
    private var items = emptyList<PickedMediaItem>()
    private var materialisedFiles = emptyList<File>()
    private var onResolved: ((List<PickedMediaItem>) -> Unit)? = null
    /** Invalidates an in-flight crop preview decode when the flow advances. */
    private var requestToken = 0L

    /** Starts the edit flow and calls [onReady] once every requested trim and
     * crop decision has been made. */
    suspend fun begin(
        picked: List<PickedMediaItem>,
        onReady: (List<PickedMediaItem>) -> Unit,
    ): Boolean {
        // A new picker/share result supersedes any trim flow that was still
        // open. It also makes a preview already decoding for that flow stale.
        clear()
        val materialised = withContext(Dispatchers.IO) { picked.map { materialise(it) } }
        materialisedFiles = materialised.mapNotNull { it.createdFile }
        val materialisedItems = materialised.map { it.item }
        val durations = withContext(Dispatchers.IO) {
            materialisedItems.map { item ->
                if (item.kind != PickedMediaKind.Video) {
                    null
                } else {
                    fileOf(item)?.let { VideoStickerConverter.durationMsOf(it) }
                }
            }
        }

        items = materialisedItems
        onResolved = onReady
        queue = knownVideoRangeIndices(durations)
        queueIndex = 0
        durationsByIndex = durations

        if (queue.isEmpty()) {
            beginCropping()
            return true
        }

        showCurrent()
        return true
    }

    private var durationsByIndex: List<Long?> = emptyList()

    fun setRange(startMs: Long, durationMs: Long) {
        val current = _request.value ?: return
        val itemIndex = queue[queueIndex]
        val range = initialVideoRange(current.durationMs, startMs, durationMs)
        _request.value = current.copy(
            startMs = range.startMs,
            selectedDurationMs = range.durationMs,
        )
        updateItemRange(itemIndex, range)
    }

    suspend fun confirm(startMs: Long, durationMs: Long) {
        val current = _request.value ?: return
        val itemIndex = queue[queueIndex]
        // Confirm receives both handles directly. That makes a quick
        // scrub-and-confirm deterministic even if the debounced player update
        // has not run yet.
        updateItemRange(
            itemIndex,
            initialVideoRange(current.durationMs, startMs, durationMs),
        )
        queueIndex++
        if (queueIndex < queue.size) {
            showCurrent()
        } else {
            beginCropping()
        }
    }

    suspend fun confirmCrop(crop: MediaCrop) {
        if (_cropRequest.value == null || cropIndex !in items.indices) return
        items = items.toMutableList().also { list ->
            list[cropIndex] = list[cropIndex].copy(crop = crop)
        }
        advanceCrop()
    }

    suspend fun keepFullImage() {
        if (_cropRequest.value == null || cropIndex !in items.indices) return
        items = items.toMutableList().also { list ->
            list[cropIndex] = list[cropIndex].copy(crop = null)
        }
        advanceCrop()
    }

    fun cancel() {
        clear()
    }

    private fun clear(deletePendingFiles: Boolean = true) {
        requestToken++
        if (deletePendingFiles) materialisedFiles.forEach(File::delete)
        _request.value = null
        _cropRequest.value = null
        onResolved = null
        queue = emptyList()
        queueIndex = 0
        cropIndex = 0
        items = emptyList()
        materialisedFiles = emptyList()
        durationsByIndex = emptyList()
    }

    private fun showCurrent() {
        val itemIndex = queue[queueIndex]
        val duration = durationsByIndex.getOrNull(itemIndex) ?: 0L
        val range = initialVideoRange(
            sourceDurationMs = duration,
            savedStartMs = items[itemIndex].trimStartMs,
            savedDurationMs = items[itemIndex].trimDurationMs,
        )
        updateItemRange(itemIndex, range)
        _request.value = TrimRequest(
            position = queueIndex + 1,
            total = queue.size,
            mediaUri = items[itemIndex].uri,
            durationMs = duration,
            startMs = range.startMs,
            selectedDurationMs = range.durationMs,
        )
    }

    private suspend fun beginCropping() {
        requestToken++
        _request.value = null
        cropIndex = 0
        showCropCurrent()
    }

    private suspend fun advanceCrop() {
        cropIndex++
        showCropCurrent()
    }

    private suspend fun showCropCurrent() {
        // A corrupt or unsupported item should still reach conversion, where
        // the existing per-sticker error handling can explain it. It simply
        // cannot offer a meaningful crop editor preview here.
        while (cropIndex < items.size) {
            val token = ++requestToken
            val item = items[cropIndex]
            _cropRequest.value = CropRequest(
                position = cropIndex + 1,
                total = items.size,
                preview = null,
                crop = item.crop,
            )
            val preview = cropPreview(item)
            if (requestToken != token) return
            if (preview != null) {
                _cropRequest.value = _cropRequest.value?.copy(preview = preview)
                return
            }
            cropIndex++
        }
        finish()
    }

    private fun finish() {
        val resolved = items
        val callback = onResolved
        // The resolved recipe still points at these app-owned files. They are
        // now owned by the receiver and must survive this coordinator reset.
        clear(deletePendingFiles = false)
        callback?.invoke(resolved)
    }

    private suspend fun cropPreview(item: PickedMediaItem): Bitmap? {
        val file = fileOf(item) ?: return null
        return withContext(Dispatchers.Default) {
            if (item.kind == PickedMediaKind.Video) {
                VideoStickerConverter.extractPreviewFrame(file, item.trimStartMs)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_PREVIEW_SIDE) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }
    }

    private fun updateItemRange(itemIndex: Int, range: VideoRange) {
        items = items.toMutableList().also {
            it[itemIndex] = it[itemIndex].copy(
                trimStartMs = range.startMs,
                trimDurationMs = range.durationMs,
            )
        }
    }

    /** Copies a picked item into app storage, unless it is already a file
     * this app owns (a share has been copied once already). */
    private fun materialise(item: PickedMediaItem): MaterialisedMedia {
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "file") return MaterialisedMedia(item)
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val destination = File(directory, UUID.randomUUID().toString())
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return MaterialisedMedia(item)
            MaterialisedMedia(
                item = item.copy(uri = Uri.fromFile(destination).toString()),
                createdFile = destination,
            )
        } catch (_: Exception) {
            destination.delete()
            MaterialisedMedia(item)
        }
    }

    private fun fileOf(item: PickedMediaItem): File? =
        Uri.parse(item.uri).path?.let(::File)?.takeIf { it.exists() }

    private companion object {
        const val DIRECTORY = "picked"
        const val MAX_PREVIEW_SIDE = 1_536
    }

    private data class MaterialisedMedia(
        val item: PickedMediaItem,
        val createdFile: File? = null,
    )
}
