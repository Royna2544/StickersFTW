package com.royna.stickersftw.conversion

import android.graphics.Bitmap

data class TimedFrame(val timestampMs: Long, val bitmap: Bitmap)

/** Decoded animation frames plus the intended complete playback duration.
 *
 * Duration is sequence-level state rather than something inferred from the
 * retained gaps: a decoder may omit frames and the size ladder deliberately
 * decimates them, but neither event is allowed to stretch or shorten the
 * loop. Null is reserved for still images or callers with no duration hint. */
data class TimedFrameSequence(
    val frames: List<TimedFrame>,
    val durationMs: Long? = null,
)
