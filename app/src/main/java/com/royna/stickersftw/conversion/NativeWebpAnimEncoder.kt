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
        val copied = bitmap.config != Bitmap.Config.ARGB_8888
        val source = if (!copied) {
            bitmap
        } else {
            // HARDWARE and RGB_565 bitmaps have no lockable RGBA_8888 buffer.
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        }
        return try {
            nativeAddFrame(handle, source, source.isPremultiplied, timestampMs)
        } finally {
            // This is a private synchronous conversion copy, so no drawable
            // or caller can still reference it after the native call returns.
            if (copied) source.recycle()
        }
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

        /** Builds a genuine two-frame animation whose decoded frames are
         * pixel-identical. The portable native core encodes [bitmap] once and
         * pushes the same compressed payload into WebPMux twice, bypassing
         * WebPAnimEncoder's duplicate-frame coalescing. */
        fun encodeRepeatedFrame(
            bitmap: Bitmap,
            frameDurationMs: Int,
            loopCount: Int,
            quality: Float,
            alphaQuality: Int,
            method: Int,
        ): ByteArray? {
            val copied = bitmap.config != Bitmap.Config.ARGB_8888
            val source = if (!copied) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            return try {
                nativeEncodeRepeatedFrame(
                    source,
                    source.isPremultiplied,
                    frameDurationMs,
                    loopCount,
                    quality,
                    alphaQuality,
                    method,
                )
            } finally {
                if (copied) source.recycle()
            }
        }

        @JvmStatic
        private external fun nativeCreate(width: Int, height: Int, loopCount: Int, minimizeSize: Boolean): Long

        @JvmStatic
        private external fun nativeConfigure(handle: Long, quality: Float, alphaQuality: Int, method: Int): Boolean

        @JvmStatic
        private external fun nativeAddFrame(
            handle: Long,
            bitmap: Bitmap,
            isPremultiplied: Boolean,
            timestampMs: Int,
        ): Boolean

        @JvmStatic
        private external fun nativeAssemble(handle: Long, totalDurationMs: Int): ByteArray?

        @JvmStatic
        private external fun nativeEncodeRepeatedFrame(
            bitmap: Bitmap,
            isPremultiplied: Boolean,
            frameDurationMs: Int,
            loopCount: Int,
            quality: Float,
            alphaQuality: Int,
            method: Int,
        ): ByteArray?

        @JvmStatic
        private external fun nativeRelease(handle: Long)
    }
}
