package com.royna.stickersftw.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.SharedMedia
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTrimCoordinatorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedFileIsCopiedOutsideReplaceableShareCache() = runBlocking {
        val sourceDirectory = File(
            context.cacheDir,
            "shared/share-isolation-${UUID.randomUUID()}",
        )
            .apply { mkdirs() }
        val source = File(sourceDirectory, "source.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val coordinator = MediaTrimCoordinator(context)
        var resolved = emptyList<PickedMediaItem>()

        coordinator.begin(
            picked = listOf(
                PickedMediaItem(
                    uri = Uri.fromFile(source).toString(),
                    kind = PickedMediaKind.Image,
                ),
            ),
            onReady = { resolved = it },
        )
        coordinator.keepFullImage()

        val prepared = File(requireNotNull(Uri.parse(resolved.single().uri).path))
        assertNotEquals(source.canonicalPath, prepared.canonicalPath)
        assertTrue(prepared.canonicalPath.startsWith(File(context.cacheDir, "picked").canonicalPath))
        sourceDirectory.deleteRecursively()
        assertTrue(prepared.isFile)

        coordinator.discardResolved(resolved)
        assertTrue(!prepared.exists())
    }

    @Test
    fun discardingOneShareBatchDoesNotDeleteAnother() {
        val root = File(context.cacheDir, "shared")
        val firstDirectory = File(root, "first-${UUID.randomUUID()}").apply { mkdirs() }
        val secondDirectory = File(root, "second-${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(firstDirectory, "media").apply { writeText("first") }
        val second = File(secondDirectory, "media").apply { writeText("second") }

        SharedMedia.discard(
            listOf(PickedMediaItem(Uri.fromFile(first).toString(), PickedMediaKind.Image)),
            context,
        )

        assertFalse(firstDirectory.exists())
        assertTrue(second.isFile)
        secondDirectory.deleteRecursively()
    }
}
