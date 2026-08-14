package com.royna.stickersftw.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaDeliveryGateTest {
    @Test
    fun recreationDoesNotClaimAnAlreadyRetainedDelivery() {
        val gate = SharedMediaDeliveryGate()
        val first = gate.beginInitial()

        assertTrue(gate.complete(requireNotNull(first), hasMedia = true))
        assertNull(gate.beginInitial())
        assertTrue(gate.isActive)
    }

    @Test
    fun staleCompletionCannotReplaceANewerDelivery() {
        val gate = SharedMediaDeliveryGate()
        val first = requireNotNull(gate.beginInitial())
        val second = gate.beginReplacement()

        assertFalse(gate.complete(first, hasMedia = true))
        assertTrue(gate.complete(second, hasMedia = true))
        assertTrue(gate.tryConsume())
        assertFalse(gate.isActive)
    }

    @Test
    fun displayedBatchCannotConsumeWhileReplacementIsCopying() {
        val gate = SharedMediaDeliveryGate()
        val first = requireNotNull(gate.beginInitial())
        assertTrue(gate.complete(first, hasMedia = true))

        val replacement = gate.beginReplacement()
        assertFalse(gate.tryConsume())
        assertTrue(gate.isActive)
        assertTrue(gate.complete(replacement, hasMedia = true))
        assertTrue(gate.tryConsume())
    }
}
