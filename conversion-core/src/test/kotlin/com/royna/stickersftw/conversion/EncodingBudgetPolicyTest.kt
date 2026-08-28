package com.royna.stickersftw.conversion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingBudgetPolicyTest {
    @Test
    fun `near miss gets one higher-effort retry`() {
        assertTrue(EncodingBudgetPolicy.shouldRetryAtHigherEffort(510_056, 500_000))
    }

    @Test
    fun `accepted and far-over-budget files skip the retry`() {
        assertFalse(EncodingBudgetPolicy.shouldRetryAtHigherEffort(487_954, 500_000))
        assertFalse(EncodingBudgetPolicy.shouldRetryAtHigherEffort(700_000, 500_000))
    }

    @Test
    fun `invalid budgets skip the retry`() {
        assertFalse(EncodingBudgetPolicy.shouldRetryAtHigherEffort(1, 0))
        assertFalse(EncodingBudgetPolicy.shouldRetryAtHigherEffort(510_056, 500_000, -1))
    }
}
