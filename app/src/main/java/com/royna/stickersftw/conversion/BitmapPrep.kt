package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap

/** Shared bitmap sizing helpers. Deliberately never calls Bitmap.recycle():
 * this pipeline converts one sticker at a time (bounding peak memory to
 * roughly one sticker's worth of frames), and manual recycling of bitmaps
 * that might still be referenced elsewhere is a hard-crash risk not worth
 * taking for a transient decode buffer the GC will reclaim shortly anyway. */
object BitmapPrep {
    /** Scales to fit a targetPx square, preserving aspect, and centres the
     * result on a transparent canvas.
     *
     * This used to centre-crop to the shorter side instead, which is only
     * lossless for a sticker that was already square. Telegram sets are full
     * of ones that are not -- a 512x316 sticker lost 38% of its width, a
     * 512x213 one would lose 58%, and what went was whatever happened to be
     * near the edges: speech bubbles, captions, the sides of a face. WhatsApp
     * needs exactly 512x512, but padding gets there without discarding
     * anything, and transparent padding is invisible against every chat
     * background. */
    fun fitSquareWithPadding(source: Bitmap, targetPx: Int): Bitmap {
        val scale = targetPx.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceIn(1, targetPx)
        val height = (source.height * scale).toInt().coerceIn(1, targetPx)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)

        if (width == targetPx && height == targetPx) return scaled

        val square = createBitmap(targetPx, targetPx)
        Canvas(square).drawBitmap(
            scaled,
            ((targetPx - width) / 2).toFloat(),
            ((targetPx - height) / 2).toFloat(),
            null,
        )
        return square
    }

    /** Scales preserving aspect ratio so the longer side equals targetLongSidePx. */
    fun aspectScale(source: Bitmap, targetLongSidePx: Int): Bitmap {
        val longSide = maxOf(source.width, source.height)
        val scale = targetLongSidePx.toFloat() / longSide
        val newWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
}
