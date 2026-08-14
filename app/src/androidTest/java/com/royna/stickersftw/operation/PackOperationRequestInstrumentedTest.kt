package com.royna.stickersftw.operation

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackOperationRequestInstrumentedTest {
    @Test
    fun addStickersRoundTripKeepsTrimStart() {
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
                ),
            ),
        )

        val restored = PackOperationRequest.readFrom(request.writeTo(Intent()))

        assertEquals(request, restored)
    }
}
