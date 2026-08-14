package com.royna.stickersftw

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentInstrumentedTest {
    @Test
    fun clearingConsumedPackIdPreservesActiveSendPayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stream = Uri.parse("content://sender.example/shared-video")
        val original = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            clipData = ClipData.newUri(context.contentResolver, "shared video", stream)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, stream)
            putExtra(MainActivity.EXTRA_PACK_ID, "notification-pack")
            putExtra("unrelated", "keep")
        }

        val cleared = original.copyWithoutPendingPackId()

        assertNotSame(original, cleared)
        assertTrue(original.hasExtra(MainActivity.EXTRA_PACK_ID))
        assertFalse(cleared.hasExtra(MainActivity.EXTRA_PACK_ID))
        assertEquals(Intent.ACTION_SEND, cleared.action)
        assertEquals("video/mp4", cleared.type)
        assertTrue(cleared.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(stream, cleared.clipData?.getItemAt(0)?.uri)
        assertTrue(cleared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("keep", cleared.getStringExtra("unrelated"))
    }
}
