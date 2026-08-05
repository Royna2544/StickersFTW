package com.royna.stickersftw.data.model

/** Shared progress shape for both the fetch-and-convert flow and the
 * create-and-publish flow -- both are "one long-running operation with a
 * stage/fraction that ends in success or failure." */
sealed class PackOperationProgress {
    data class Progress(val stage: String, val fraction: Float) : PackOperationProgress()
    data class Complete(val packId: String) : PackOperationProgress()
    data class Failed(val message: String) : PackOperationProgress()
}
