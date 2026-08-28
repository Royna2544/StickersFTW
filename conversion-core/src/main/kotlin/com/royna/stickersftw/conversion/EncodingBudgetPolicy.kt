package com.royna.stickersftw.conversion

/** Pure byte-budget decisions shared with platform encoders. */
object EncodingBudgetPolicy {
    fun shouldRetryAtHigherEffort(
        lastSize: Int,
        maxBytes: Int,
        marginPercent: Int = 10,
    ): Boolean {
        if (lastSize <= maxBytes || maxBytes <= 0 || marginPercent < 0) return false
        val nearMissLimit = maxBytes.toLong() * (100L + marginPercent) / 100L
        return lastSize.toLong() <= nearMissLimit
    }
}
