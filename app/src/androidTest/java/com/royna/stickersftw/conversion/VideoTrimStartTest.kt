package com.royna.stickersftw.conversion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A clip longer than a sticker may be gets one window of itself, and which
 * window is the user's to choose. Before the trim existed the answer was
 * always the opening seconds, so a regression here is silent: the conversion
 * still succeeds, it just quietly returns the wrong part of the video.
 *
 * Needs a real clip with real motion. `cache/testmedia/motion.mp4` is pushed
 * in by hand rather than bundled, because a static test asset would not prove
 * much -- sparse video encodes only what changes, and a clip that sits still
 * can go seconds between frames.
 */
@RunWith(AndroidJUnit4::class)
class VideoTrimStartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clip = File(context.cacheDir, "testmedia/motion.mp4")

    @Test
    fun aLaterStartYieldsADifferentPartOfTheClip() = runBlocking {
        assumeTrue("no test clip pushed", clip.exists())

        val duration = VideoStickerConverter.durationMsOf(clip)
        assertNotNull("clip reports no duration", duration)
        assertTrue(
            "clip must be longer than one sticker to have anything to choose",
            duration!! > SizeBudget.MAX_TOTAL_DURATION_MS,
        )

        val fromStart = VideoStickerConverter.extractFrames(clip, SizeBudget.STICKER_PX, startMs = 0L)
        val fromMiddle = VideoStickerConverter.extractFrames(clip, SizeBudget.STICKER_PX, startMs = 10_000L)

        assertTrue("no frames at the start", !fromStart.isNullOrEmpty())
        assertTrue("no frames ten seconds in", !fromMiddle.isNullOrEmpty())

        // Timestamps are rebased, so both windows read as a sticker starting at
        // zero -- otherwise the encoder would write ten seconds of dead time
        // in front of the second one.
        assertEquals("trimmed sticker should still start at zero", 0L, fromMiddle!!.first().timestampMs)

        val startPixels = fromStart!!.first().bitmap.let { bitmap ->
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        val middlePixels = fromMiddle.first().bitmap.let { bitmap ->
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        assertNotEquals(
            "the two windows returned the same first frame, so the start offset did nothing",
            startPixels.toList(),
            middlePixels.toList(),
        )
    }
}
