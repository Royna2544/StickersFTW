package com.royna.stickersftw

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedMediaInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun failedCopyDoesNotLeaveItsBatchDirectory() = runBlocking {
        val root = File(context.cacheDir, "shared").apply { mkdirs() }
        val before = root.listFiles().orEmpty().map(File::getName).toSet()
        val missing = File(context.cacheDir, "missing-${UUID.randomUUID()}")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(missing))
        }

        assertTrue(SharedMedia.ingest(intent, context).isEmpty())

        val after = root.listFiles().orEmpty().map(File::getName).toSet()
        assertEquals(before, after)
    }

    @Test
    fun discardDeletesOnlyTheItemsBatch() {
        val root = File(context.cacheDir, "shared").apply { mkdirs() }
        val first = File(root, "test-${UUID.randomUUID()}").apply { mkdirs() }
        val second = File(root, "test-${UUID.randomUUID()}").apply { mkdirs() }
        val firstItem = File(first, "item").apply { writeBytes(byteArrayOf(1)) }
        File(second, "item").writeBytes(byteArrayOf(2))

        try {
            SharedMedia.discard(
                listOf(
                    PickedMediaItem(
                        uri = Uri.fromFile(firstItem).toString(),
                        kind = PickedMediaKind.Image,
                    ),
                ),
                context,
            )

            assertFalse(first.exists())
            assertTrue(second.exists())
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }
}
