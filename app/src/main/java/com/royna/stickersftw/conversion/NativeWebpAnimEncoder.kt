package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import java.io.Closeable

/** Thin binding over `webp_anim_encoder.c`.
 *
 * Replaces com.aureusapps.android:webp-android, whose prebuilt .so files were
 * linked at a 4KB page size and so cannot load on a 16KB-page device. That is
 * only fixable by relinking, upstream stopped at 1.1.2, and this app used
 * exactly one of its APIs -- so the encoder is built here instead, from
 * libwebp, and everything else that AAR carried is gone.
 *
 * Not thread-safe: one instance owns one native encoder, used from one
 * coroutine at a time, which is how the conversion pipeline works anyway. */
class NativeWebpAnimEncoder private constructor(
    private var handle: Long,
    private val width: Int,
    private val height: Int,
) : Closeable {

    fun configure(quality: Float, alphaQuality: Int, method: Int): Boolean {
        check(handle != 0L) { "Encoder already released" }
        return nativeConfigure(handle, quality, alphaQuality, method)
    }

    /** [bitmap] must already be [width] x [height]; the native side rejects
     * a mismatch rather than scaling, since silently resampling frames would
     * hide a real bug in the caller. */
    fun addFrame(bitmap: Bitmap, timestampMs: Int): Boolean {
        check(handle != 0L) { "Encoder already released" }
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            // HARDWARE and RGB_565 bitmaps have no lockable RGBA_8888 buffer.
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return nativeAddFrame(handle, source, timestampMs)
    }

    fun assemble(totalDurationMs: Int): ByteArray? {
        check(handle != 0L) { "Encoder already released" }
        return nativeAssemble(handle, totalDurationMs)
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("stickerwebp")
        }

        fun create(width: Int, height: Int, loopCount: Int, minimizeSize: Boolean): NativeWebpAnimEncoder? {
            val handle = nativeCreate(width, height, loopCount, minimizeSize)
            return if (handle == 0L) null else NativeWebpAnimEncoder(handle, width, height)
        }

        @JvmStatic
        private external fun nativeCreate(width: Int, height: Int, loopCount: Int, minimizeSize: Boolean): Long

        @JvmStatic
        private external fun nativeConfigure(handle: Long, quality: Float, alphaQuality: Int, method: Int): Boolean

        @JvmStatic
        private external fun nativeAddFrame(handle: Long, bitmap: Bitmap, timestampMs: Int): Boolean

        @JvmStatic
        private external fun nativeAssemble(handle: Long, totalDurationMs: Int): ByteArray?

        @JvmStatic
        private external fun nativeRelease(handle: Long)
    }
}
