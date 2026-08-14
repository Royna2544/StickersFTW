package com.royna.stickersftw.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateMediaOwnershipTest {
    @Test
    fun activeDisposalAndLatePreparedMediaAreDiscarded() {
        val ownership = CreateMediaOwnership()

        assertTrue(ownership.canRetainPreparedMedia())
        assertTrue(ownership.disposeShouldDiscard())
        assertFalse(ownership.canRetainPreparedMedia())
    }

    @Test
    fun pendingDisposalDefersToFailureWhichDiscards() {
        val ownership = CreateMediaOwnership()

        ownership.beginSubmission()
        assertFalse(ownership.canRetainPreparedMedia())
        assertFalse(ownership.disposeShouldDiscard())
        assertTrue(ownership.submissionFailedShouldDiscard())
    }

    @Test
    fun activeFailureRetainsForRetryAndSuccessfulStartTransfersOwnership() {
        val retry = CreateMediaOwnership()
        retry.beginSubmission()
        assertFalse(retry.submissionFailedShouldDiscard())
        assertTrue(retry.canRetainPreparedMedia())

        val started = CreateMediaOwnership()
        started.beginSubmission()
        started.submissionStarted()
        assertFalse(started.disposeShouldDiscard())
        assertFalse(started.canRetainPreparedMedia())
    }

    @Test
    fun synchronousRejectionRestoresActiveOwnership() {
        val ownership = CreateMediaOwnership()

        ownership.beginSubmission()
        ownership.rejectSynchronously()

        assertTrue(ownership.canRetainPreparedMedia())
        assertTrue(ownership.disposeShouldDiscard())
    }
}
