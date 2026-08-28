package com.royna.stickersftw.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionVersionFreshnessTest {
    @Test
    fun readyImportedPackNeedsReconversionWhenBuildIsUnknownOrOlder() {
        assertTrue(needsReconversion(convertedAppVersionCode = null))
        assertTrue(needsReconversion(convertedAppVersionCode = 4))
    }

    @Test
    fun equalOrNewerImportedBuildDoesNotNeedReconversion() {
        assertFalse(needsReconversion(convertedAppVersionCode = 5))
        assertFalse(needsReconversion(convertedAppVersionCode = 6))
    }

    @Test
    fun onlyReadyImportedPacksAreEligible() {
        assertFalse(
            deriveNeedsReconversion(
                origin = PackOrigin.Created,
                status = PackStatus.Ready,
                convertedAppVersionCode = null,
                currentAppVersionCode = 5,
            ),
        )
        assertFalse(
            deriveNeedsReconversion(
                origin = PackOrigin.Imported,
                status = PackStatus.Failed,
                convertedAppVersionCode = null,
                currentAppVersionCode = 5,
            ),
        )
    }

    private fun needsReconversion(convertedAppVersionCode: Int?): Boolean =
        deriveNeedsReconversion(
            origin = PackOrigin.Imported,
            status = PackStatus.Ready,
            convertedAppVersionCode = convertedAppVersionCode,
            currentAppVersionCode = 5,
        )
}
