package com.royna.stickersftw.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/** Static-image conversion for both destinations: WhatsApp wants an exact
 * 512x512 square; Telegram wants the longer side exactly 512 with aspect
 * preserved. Both step down WEBP quality until the size budget is met. */
object StaticStickerConverter {
    fun convert(input: File, output: File, targetPx: Int, maxBytes: Int): ConversionOutcome {
        val source = BitmapFactory.decodeFile(input.absolutePath)
            ?: return ConversionOutcome.Failed("Could not decode image.")
        val square = BitmapPrep.fitSquareWithPadding(source, targetPx)
        return compressWithBudget(square, output, maxBytes)
    }

    fun convertAspectPreserving(
        input: File,
        output: File,
        targetLongSidePx: Int,
        maxBytes: Int,
    ): ConversionOutcome {
        val source = BitmapFactory.decodeFile(input.absolutePath)
            ?: return ConversionOutcome.Failed("Could not decode image.")
        val scaled = BitmapPrep.aspectScale(source, targetLongSidePx)
        return compressWithBudget(scaled, output, maxBytes)
    }

    fun compressWithBudget(bitmap: Bitmap, output: File, maxBytes: Int): ConversionOutcome {
        output.parentFile?.mkdirs()

        var lastBytes: ByteArray? = null
        for (quality in SizeBudget.QUALITY_STEPS) {
            val bytes = compressToBytes(bitmap, quality)
            lastBytes = bytes
            if (bytes.size <= maxBytes) {
                output.writeBytes(bytes)
                return ConversionOutcome.Success(bytes.size)
            }
        }

        val bytes = lastBytes ?: return ConversionOutcome.Failed("Could not encode image.")
        output.writeBytes(bytes)
        return ConversionOutcome.Success(
            bytes.size,
            warning = "Sticker is ${bytes.size / 1024}KB, over the ${maxBytes / 1024}KB budget.",
        )
    }

    private fun compressToBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, stream)
        return stream.toByteArray()
    }
}
