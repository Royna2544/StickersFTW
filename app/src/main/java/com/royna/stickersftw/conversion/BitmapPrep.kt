package com.royna.stickersftw.conversion

import android.graphics.Bitmap

/** Shared bitmap sizing helpers. Deliberately never calls Bitmap.recycle():
 * this pipeline converts one sticker at a time (bounding peak memory to
 * roughly one sticker's worth of frames), and manual recycling of bitmaps
 * that might still be referenced elsewhere is a hard-crash risk not worth
 * taking for a transient decode buffer the GC will reclaim shortly anyway. */
object BitmapPrep {
    /** Center-crops to a square, then scales to targetPx x targetPx. */
    fun centerCropSquareAndScale(source: Bitmap, targetPx: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        return Bitmap.createScaledBitmap(cropped, targetPx, targetPx, true)
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
