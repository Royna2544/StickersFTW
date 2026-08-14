package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.royna.stickersftw.model.MediaCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapCropInstrumentedTest {
    @Test
    fun normalizedCropIsAppliedBeforeSquareSizing() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                source.setPixel(x, y, if (x < 200) Color.RED else Color.BLUE)
            }
        }

        val result = BitmapPrep.cropAndFitSquare(
            source,
            targetPx = 96,
            crop = MediaCrop(left = 0f, top = 0f, right = 0.5f, bottom = 1f),
        )

        assertEquals(96, result.width)
        assertEquals(96, result.height)
        val center = result.getPixel(48, 48)
        assertTrue("left-half crop should not retain blue", Color.red(center) > Color.blue(center))
    }
}
