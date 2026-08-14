package com.royna.stickersftw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Takes media out of an [Intent.ACTION_SEND] / [Intent.ACTION_SEND_MULTIPLE]
 * and puts it somewhere the rest of the app can still reach.
 *
 * The URIs arrive with a read grant tied to the delivering intent and to the
 * task that received it. Conversion happens later and in a *service*, by which
 * point the grant may be gone -- which is not a hypothetical: the first
 * version of this passed the content:// URIs straight through and every share
 * failed with "no stickers could be converted", because nothing could open
 * them by the time it mattered.
 *
 * So each one is copied into app-private storage while the grant is
 * definitely valid, and what the app carries around afterwards is a file it
 * owns. The copies land in the cache directory, since the conversion makes
 * its own copy inside the pack and these are dead the moment it does.
 */
object SharedMedia {
    private const val DIRECTORY = "shared"

    suspend fun ingest(intent: Intent?, context: Context): List<PickedMediaItem> {
        var batchDirectory: File? = null
        var ownershipTransferred = false
        try {
            val copied = withContext(Dispatchers.IO) {
                val incoming = parse(intent, context)
                if (incoming.isEmpty()) return@withContext emptyList()

                // Each delivery owns a separate directory. Android can deliver a
                // second ACTION_SEND while a large first clip is still copying;
                // sharing one destructively-cleared directory makes those two
                // ingests race and can invalidate the older open editor.
                val root = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
                val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                batchDirectory = directory

                incoming.mapNotNull { (uri, kind) ->
                    val destination = File(directory, UUID.randomUUID().toString())
                    val itemCopied = try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    } catch (cancelled: CancellationException) {
                        destination.delete()
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (itemCopied && destination.length() > 0) {
                        PickedMediaItem(uri = Uri.fromFile(destination).toString(), kind = kind)
                    } else {
                        destination.delete()
                        null
                    }
                }
            }

            if (copied.isEmpty()) return emptyList()
            ownershipTransferred = true
            return copied
        } finally {
            // withContext has prompt cancellation: its IO block may finish
            // after the Activity is gone yet never deliver the result. Until
            // the list reaches the caller, this function still owns the whole
            // unique directory and must reclaim it.
            if (!ownershipTransferred) {
                withContext(NonCancellable + Dispatchers.IO) {
                    batchDirectory?.deleteRecursively()
                }
            }
        }
    }

    /** Deletes only the batch that owns [items], never a newer share being
     * ingested alongside it. All accepted paths must remain below cache/shared. */
    fun discard(items: List<PickedMediaItem>, context: Context) {
        if (items.isEmpty()) return
        val root = runCatching { File(context.cacheDir, DIRECTORY).canonicalFile }.getOrNull()
            ?: return
        val rootPrefix = root.path + File.separator
        val files = items.mapNotNull { item ->
            val uri = Uri.parse(item.uri)
            if (uri.scheme != "file") return@mapNotNull null
            uri.path?.let(::File)?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf { it.path.startsWith(rootPrefix) }
        }
        files.mapNotNull(File::getParentFile).distinctBy(File::getPath).forEach { parent ->
            if (parent.parentFile == root) parent.deleteRecursively()
        }
        // Compatibility with shares copied before per-batch directories.
        files.filter { it.parentFile == root }.forEach(File::delete)
    }

    private fun parse(intent: Intent?, context: Context): List<Pair<Uri, PickedMediaKind>> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUris(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        return uris.mapNotNull { uri ->
            // Prefer what the provider says over what the sender declared: a
            // share of mixed media carries one type for the whole batch, so
            // the intent's own type cannot tell a clip from a photo.
            val mimeType = context.contentResolver.getType(uri) ?: intent.type
            when {
                mimeType == null -> null
                mimeType.startsWith("video/") -> uri to PickedMediaKind.Video
                mimeType.startsWith("image/") -> uri to PickedMediaKind.Image
                else -> null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Uri::class.java)
        } else {
            getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUris(key: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
}
