package com.royna.stickersftw.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaPreparationDeliveryGateTest {
    @Test
    fun replacementThatTriedBeforeReleaseIsWokenAndClaimsExactlyOnce() {
        val gate = MediaPreparationDeliveryGate()
        val request = requireNotNull(gate.begin())

        assertTrue(gate.publish(request))
        assertTrue(gate.tryClaim(request))

        // Replacement B is already composed, observes the completion, and
        // loses its first claim race to still-active owner A.
        val revisionBeforeRelease = gate.deliveryRevision.value
        assertFalse(gate.tryClaim(request))

        // Activity recreation cancels the first composition before it hands
        // the prepared files to a service. Releasing, rather than finishing,
        // keeps the completion and emits the retry wake-up B observes.
        assertTrue(gate.release(request))
        assertTrue(gate.deliveryRevision.value > revisionBeforeRelease)
        assertTrue(gate.tryClaim(request))
        assertTrue(gate.finish(request))

        assertFalse(gate.tryClaim(request))
        assertFalse(gate.finish(request))
        assertNotEquals(request, requireNotNull(gate.begin()))
    }

    @Test
    fun staleCompletionCannotPublishAfterCancellationAndNewRequest() {
        val gate = MediaPreparationDeliveryGate()
        val stale = requireNotNull(gate.begin())
        gate.invalidate()
        val current = requireNotNull(gate.begin())

        assertFalse(gate.publish(stale))
        assertTrue(gate.publish(current))
    }

    @Test
    fun routeReplacementInvalidatesOwnerlessCompletion() {
        val gate = MediaPreparationDeliveryGate()
        val createRequest = requireNotNull(gate.begin())
        assertTrue(gate.publish(createRequest))

        // Route replacement mirrors AppViewModel.cancelTrim(): the retained
        // Create completion is invalidated before its destination is removed.
        gate.invalidate()

        assertFalse(gate.tryClaim(createRequest))
        assertFalse(gate.finish(createRequest))
        assertFalse(gate.isActive)
        assertTrue(gate.begin() != null)
    }

    @Test
    fun ordinaryStickerSourceFailureUnwindsGeneration() = runBlocking {
        val gate = MediaPreparationDeliveryGate()
        val request = requireNotNull(gate.begin())
        var failures = 0

        val result = guardedMediaPreparationSource<String>(
            onFailure = {
                failures++
                gate.finish(request)
            },
        ) { throw IllegalStateException("lookup failed") }

        assertNull(result)
        assertEquals(1, failures)
        assertFalse(gate.isActive)
        assertTrue(gate.begin() != null)
    }

    @Test
    fun stickerSourceCancellationUnwindsAndRethrows() {
        val gate = MediaPreparationDeliveryGate()
        val request = requireNotNull(gate.begin())
        var failures = 0

        try {
            runBlocking {
                guardedMediaPreparationSource<String>(
                    onFailure = {
                        failures++
                        gate.finish(request)
                    },
                ) { throw CancellationException("owner cleared") }
            }
            fail("CancellationException was not rethrown")
        } catch (_: CancellationException) {
            // Expected: cleanup must not swallow structured cancellation.
        }

        assertEquals(1, failures)
        assertFalse(gate.isActive)
    }
}
