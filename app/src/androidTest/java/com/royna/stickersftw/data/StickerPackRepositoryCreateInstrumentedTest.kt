package com.royna.stickersftw.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.data.local.AppDatabase
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerPackRepositoryCreateInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = AppDatabase.getInstance(context)
    private val repository = StickerPackRepository(context)

    @Test
    fun discardingUnstartedCreateRemovesRowsButPreservesPreparedInputs() = runBlocking {
        val preparedDirectory = File(context.cacheDir, "picked/create-test-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val prepared = (0 until 3).map { index ->
            File(preparedDirectory, "$index.png").apply { writeBytes(byteArrayOf(index.toByte())) }
        }
        var packId: String? = null
        try {
            val createdPackId = repository.createPack(
                items = prepared.map { file ->
                    PickedMediaItem(Uri.fromFile(file).toString(), PickedMediaKind.Image)
                },
                title = "Retryable create",
                shortName = "retryable_create",
            )
            packId = createdPackId

            assertEquals(PackStatus.Building.name, database.packDao().getPack(createdPackId)?.status)
            assertEquals(3, database.stickerDao().getStickersOnce(createdPackId).size)

            assertTrue(repository.discardUnstartedCreatedPack(createdPackId))

            assertNull(database.packDao().getPack(createdPackId))
            assertTrue(database.stickerDao().getStickersOnce(createdPackId).isEmpty())
            assertTrue(prepared.all(File::isFile))
        } finally {
            packId?.let { database.packDao().delete(it) }
            preparedDirectory.deleteRecursively()
        }
    }
}
