package com.royna.stickersftw.conversion

import android.graphics.Bitmap

data class TimedFrame(val timestampMs: Long, val bitmap: Bitmap)

sealed class ConversionOutcome {
    data class Success(val bytesWritten: Int, val warning: String? = null) : ConversionOutcome()
    data class Failed(val reason: String) : ConversionOutcome()
}

sealed class StickerConvertResult {
    data class Success(
        val convertedPath: String,
        val warning: String? = null,
        /** Whether the output genuinely ended up multi-frame -- decided by
         * how many usable frames extraction actually produced, not by the
         * source's nominal format. A pack's animated/static classification
         * should be derived from this (real outcome), never from what the
         * source claimed to be, so the pack's declared metadata always
         * matches what WhatsApp/Telegram actually receive. */
        val isAnimated: Boolean = false,
    ) : StickerConvertResult()
    data class Failed(val reason: String) : StickerConvertResult()
}
