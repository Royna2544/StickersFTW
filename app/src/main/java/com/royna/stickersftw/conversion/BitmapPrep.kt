package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import com.royna.stickersftw.model.MediaCrop
import kotlin.math.roundToInt

/** Shared bitmap sizing helpers. Deliberately never calls Bitmap.recycle():
 * this pipeline converts one sticker at a time (bounding peak memory to
 * roughly one sticker's worth of frames), and manual recycling of bitmaps
 * that might still be referenced elsewhere is a hard-crash risk not worth
 * taking for a transient decode buffer the GC will reclaim shortly anyway. */
object BitmapPrep {
    /** Applies a source-relative crop, defensively clamping values restored
     * from storage or an operation Intent. Invalid rectangles keep the source
     * instead of turning one bad edit into a failed sticker conversion. */
    fun crop(source: Bitmap, crop: MediaCrop?): Bitmap {
        crop ?: return source
        val left = crop.left.coerceIn(0f, 1f)
        val top = crop.top.coerceIn(0f, 1f)
        val right = crop.right.coerceIn(0f, 1f)
        val bottom = crop.bottom.coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return source

        val x = (left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val y = (top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val rightPx = (right * source.width).roundToInt().coerceIn(x + 1, source.width)
        val bottomPx = (bottom * source.height).roundToInt().coerceIn(y + 1, source.height)
        if (x == 0 && y == 0 && rightPx == source.width && bottomPx == source.height) return source
        return Bitmap.createBitmap(source, x, y, rightPx - x, bottomPx - y)
    }

    fun cropAndFitSquare(source: Bitmap, targetPx: Int, crop: MediaCrop?): Bitmap =
        fitSquareWithPadding(crop(source, crop), targetPx)

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
