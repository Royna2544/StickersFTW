package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the JNI encoder that replaced the webp-android AAR, whose prebuilt
 * libraries could not load on a 16KB-page device.
 *
 * Asserts on the actual container bytes rather than just "didn't crash":
 * getting an encoder to emit *something* is easy, and the two ways this could
 * silently go wrong -- collapsing to a single-frame still, or dropping the
 * alpha channel -- both produce a perfectly valid file. */
@RunWith(AndroidJUnit4::class)
class NativeWebpAnimEncoderInstrumentedTest {

    private fun frame(color: Int, transparentHalf: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val clear = transparentHalf && x < SIZE / 2
                bitmap.setPixel(x, y, if (clear) Color.TRANSPARENT else color)
            }
        }
        return bitmap
    }

    private fun encode(frames: List<Pair<Bitmap, Int>>, totalMs: Int): ByteArray {
        val encoder = NativeWebpAnimEncoder.create(SIZE, SIZE, loopCount = 0, minimizeSize = true)
        assertNotNull("encoder failed to initialise", encoder)
        return requireNotNull(encoder).use {
            assertTrue(it.configure(quality = 80f, alphaQuality = 100, method = 4))
            frames.forEach { (bitmap, ts) -> assertTrue(it.addFrame(bitmap, ts)) }
            requireNotNull(it.assemble(totalMs)) { "assemble returned null" }
        }
    }

    /** RIFF....WEBP, then chunk ids. ANIM only exists in an extended-format
     * file, which is exactly what a single-frame collapse would lack. */
    private fun chunks(bytes: ByteArray): List<String> {
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        val found = mutableListOf<String>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            found += id
            var size = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            if (id == "VP8X") {
                offset += 8 + size + (size and 1)
                continue
            }
            if (id == "ANMF") {
                // Frames nest their payload; step inside rather than over.
                offset += 8 + 16
                continue
            }
            if (size < 0) break
            offset += 8 + size + (size and 1)
        }
        return found
    }

    @Test
    fun encodesMultipleFramesAsAnAnimation() {
        val bytes = encode(
            listOf(
                frame(Color.RED, transparentHalf = false) to 0,
                frame(Color.GREEN, transparentHalf = false) to 100,
                frame(Color.BLUE, transparentHalf = false) to 200,
            ),
            totalMs = 300,
        )

        val ids = chunks(bytes)
        assertTrue("expected VP8X extended format, got $ids", "VP8X" in ids)
        assertTrue("expected ANIM chunk, got $ids", "ANIM" in ids)
        assertEquals("expected three animation frames, got $ids", 3, ids.count { it == "ANMF" })
    }

    @Test
    fun preservesTransparency() {
        val bytes = encode(
            listOf(
                frame(Color.RED, transparentHalf = true) to 0,
                frame(Color.BLUE, transparentHalf = true) to 100,
            ),
            totalMs = 200,
        )

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("output did not decode", decoded)
        requireNotNull(decoded)
        assertTrue("bitmap reports no alpha channel", decoded.hasAlpha())
        assertEquals("left half should be transparent", 0, Color.alpha(decoded.getPixel(2, SIZE / 2)))
        assertEquals("right half should be opaque", 255, Color.alpha(decoded.getPixel(SIZE - 3, SIZE / 2)))
    }

    /** A frame whose size doesn't match the encoder is a caller bug. Silently
     * rescaling it would hide that, so the native side refuses. */
    @Test
    fun rejectsMismatchedFrameSize() {
        val encoder = requireNotNull(
            NativeWebpAnimEncoder.create(SIZE, SIZE, loopCount = 0, minimizeSize = true),
        )
        encoder.use {
            assertTrue(it.configure(quality = 80f, alphaQuality = 100, method = 4))
            val wrong = Bitmap.createBitmap(SIZE / 2, SIZE, Bitmap.Config.ARGB_8888)
            assertTrue("mismatched frame should be rejected", !it.addFrame(wrong, 0))
        }
    }

    private companion object {
        const val SIZE = 64
    }
}
