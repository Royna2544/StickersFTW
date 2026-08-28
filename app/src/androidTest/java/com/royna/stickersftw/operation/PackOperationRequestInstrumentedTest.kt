package com.royna.stickersftw.operation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.royna.stickersftw.model.MediaCrop
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackOperationRequestInstrumentedTest {
    @Test
    fun reconvertRoundTripKeepsPackIdentity() {
        val request = PackOperationRequest.Reconvert(
            packId = "imported-pack-id",
            packTitle = "Imported Pack",
        )

        val restored = PackOperationRequest.readFrom(request.writeTo(Intent()))

        assertEquals(request, restored)
    }

    @Test
    fun addStickersRoundTripKeepsEdits() {
        val request = PackOperationRequest.AddStickers(
            packId = "pack-id",
            packTitle = "Pack",
            items = listOf(
                PickedMediaItem("file:///still.png", PickedMediaKind.Image, emoji = "🍒"),
                PickedMediaItem(
                    "file:///clip.mp4",
                    PickedMediaKind.Video,
                    emoji = "🎬",
                    trimStartMs = 9_876L,
                    trimDurationMs = 4_321L,
                    crop = MediaCrop(0.1f, 0.2f, 0.7f, 0.8f),
                ),
            ),
        )

        val restored = PackOperationRequest.readFrom(request.writeTo(Intent()))

        assertEquals(request, restored)
    }

    @Test
    fun addStickersFromOlderIntentUsesLegacyDuration() {
        val request = PackOperationRequest.AddStickers(
            packId = "pack-id",
            packTitle = "Pack",
            items = listOf(
                PickedMediaItem(
                    "file:///clip.mp4",
                    PickedMediaKind.Video,
                    trimStartMs = 1_000L,
                    trimDurationMs = 2_000L,
                ),
            ),
        )
        val oldIntent = request.writeTo(Intent()).apply {
            removeExtra("itemTrimDurations")
        }

        val restored = PackOperationRequest.readFrom(oldIntent) as PackOperationRequest.AddStickers

        assertEquals(0L, restored.items.single().trimDurationMs)
    }

    @Test
    fun editStickerRoundTripKeepsTargetAndRecipe() {
        val request = PackOperationRequest.EditSticker(
            packId = "pack-id",
            packTitle = "Pack",
            rowId = 42L,
            item = PickedMediaItem(
                uri = "file:///edited.mp4",
                kind = PickedMediaKind.Video,
                emoji = "🎞️",
                trimStartMs = 1_250L,
                trimDurationMs = 2_750L,
                crop = MediaCrop(0.15f, 0.2f, 0.85f, 0.9f),
            ),
        )

        val restored = PackOperationRequest.readFrom(request.writeTo(Intent()))

        assertEquals(request, restored)
    }
}
