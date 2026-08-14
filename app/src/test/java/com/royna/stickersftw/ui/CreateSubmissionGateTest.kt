package com.royna.stickersftw.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSubmissionGateTest {
    @Test
    fun rejectsDuplicatesButDoesNotConsumeSynchronousAvailabilityRejection() {
        val gate = CreateSubmissionGate()

        assertFalse(gate.tryAccept(operationAvailable = false))
        assertTrue(gate.tryAccept(operationAvailable = true))
        assertFalse(gate.tryAccept(operationAvailable = true))

        gate.release()
        assertTrue(gate.tryAccept(operationAvailable = true))
    }
}
