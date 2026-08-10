package com.royna.stickersftw.data.model

/** Shared progress shape for both the fetch-and-convert flow and the
 * create-and-publish flow -- both are "one long-running operation with a
 * stage/fraction that ends in success or failure." */
sealed class PackOperationProgress {
    /** [slowFormat] is set once the pipeline knows the pack contains video
     * stickers, which are far slower than the rest: each frame is decoded
     * twice (picture and transparency) and the pack is re-encoded down a
     * quality ladder until every sticker fits WhatsApp's size cap. It is what
     * lets the UI say so up front instead of looking stuck. */
    data class Progress(
        val stage: String,
        val fraction: Float,
        val slowFormat: Boolean = false,
    ) : PackOperationProgress()
    data class Complete(val packId: String) : PackOperationProgress()
    data class Failed(val message: String) : PackOperationProgress()
}
