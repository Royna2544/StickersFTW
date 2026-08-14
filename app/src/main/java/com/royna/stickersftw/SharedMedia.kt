package com.royna.stickersftw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    suspend fun ingest(intent: Intent?, context: Context): List<PickedMediaItem> =
        withContext(Dispatchers.IO) {
            val incoming = parse(intent, context)
            if (incoming.isEmpty()) return@withContext emptyList()

            val directory = File(context.cacheDir, DIRECTORY)
            // Anything still here is from a share that has already been dealt
            // with, one way or another.
            directory.deleteRecursively()
            directory.mkdirs()

            incoming.mapNotNull { (uri, kind) ->
                val destination = File(directory, UUID.randomUUID().toString())
                val copied = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } != null
                } catch (_: Exception) {
                    false
                }
                if (copied && destination.length() > 0) {
                    PickedMediaItem(uri = Uri.fromFile(destination).toString(), kind = kind)
                } else {
                    destination.delete()
                    null
                }
            }
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
