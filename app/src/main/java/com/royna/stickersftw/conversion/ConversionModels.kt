package com.royna.stickersftw.conversion

import android.graphics.Bitmap

data class TimedFrame(val timestampMs: Long, val bitmap: Bitmap)

sealed class ConversionOutcome {
    data class Success(val bytesWritten: Int, val warning: String? = null) : ConversionOutcome()
    data class Failed(val reason: String) : ConversionOutcome()
}

sealed class StickerConvertResult {
    data class Success(val convertedPath: String, val warning: String? = null) : StickerConvertResult()
    data class Failed(val reason: String) : StickerConvertResult()
}
