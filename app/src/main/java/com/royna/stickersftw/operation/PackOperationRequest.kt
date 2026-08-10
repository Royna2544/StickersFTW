package com.royna.stickersftw.operation

import android.content.Intent

/** A pack operation described as data rather than a lambda.
 *
 * It used to be a `() -> Flow<PackOperationProgress>` closed over the
 * ViewModel, which is exactly why the work died with the ViewModel. Handing
 * it to a service means it has to survive an Intent, so everything the
 * operation needs is either in here or re-read from settings when it starts.
 * Backend config, bias and Telegram user id are deliberately *not* carried:
 * the service reads them itself, so there is no chance of a stale copy. */
sealed class PackOperationRequest {
    abstract val packId: String
    abstract val packTitle: String

    data class Import(
        override val packId: String,
        override val packTitle: String,
        val input: String,
        val partIndex: Int,
    ) : PackOperationRequest()

    data class ImportCustom(
        override val packId: String,
        override val packTitle: String,
        val input: String,
        val selectedIds: Set<String>,
    ) : PackOperationRequest()

    data class Update(
        override val packId: String,
        override val packTitle: String,
    ) : PackOperationRequest()

    data class Publish(
        override val packId: String,
        override val packTitle: String,
        val pushToTelegram: Boolean,
        val addToWhatsapp: Boolean,
    ) : PackOperationRequest()

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(KEY_PACK_ID, packId)
        putExtra(KEY_PACK_TITLE, packTitle)
        when (this@PackOperationRequest) {
            is Import -> {
                putExtra(KEY_KIND, KIND_IMPORT)
                putExtra(KEY_INPUT, input)
                putExtra(KEY_PART_INDEX, partIndex)
            }
            is ImportCustom -> {
                putExtra(KEY_KIND, KIND_IMPORT_CUSTOM)
                putExtra(KEY_INPUT, input)
                putExtra(KEY_SELECTED_IDS, selectedIds.toTypedArray())
            }
            is Update -> putExtra(KEY_KIND, KIND_UPDATE)
            is Publish -> {
                putExtra(KEY_KIND, KIND_PUBLISH)
                putExtra(KEY_PUSH_TELEGRAM, pushToTelegram)
                putExtra(KEY_ADD_WHATSAPP, addToWhatsapp)
            }
        }
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_PACK_ID = "packId"
        private const val KEY_PACK_TITLE = "packTitle"
        private const val KEY_INPUT = "input"
        private const val KEY_PART_INDEX = "partIndex"
        private const val KEY_SELECTED_IDS = "selectedIds"
        private const val KEY_PUSH_TELEGRAM = "pushTelegram"
        private const val KEY_ADD_WHATSAPP = "addWhatsapp"

        private const val KIND_IMPORT = "import"
        private const val KIND_IMPORT_CUSTOM = "importCustom"
        private const val KIND_UPDATE = "update"
        private const val KIND_PUBLISH = "publish"

        fun readFrom(intent: Intent?): PackOperationRequest? {
            val packId = intent?.getStringExtra(KEY_PACK_ID) ?: return null
            val packTitle = intent.getStringExtra(KEY_PACK_TITLE).orEmpty()
            return when (intent.getStringExtra(KEY_KIND)) {
                KIND_IMPORT -> Import(
                    packId,
                    packTitle,
                    intent.getStringExtra(KEY_INPUT).orEmpty(),
                    intent.getIntExtra(KEY_PART_INDEX, 0),
                )
                KIND_IMPORT_CUSTOM -> ImportCustom(
                    packId,
                    packTitle,
                    intent.getStringExtra(KEY_INPUT).orEmpty(),
                    intent.getStringArrayExtra(KEY_SELECTED_IDS).orEmpty().toSet(),
                )
                KIND_UPDATE -> Update(packId, packTitle)
                KIND_PUBLISH -> Publish(
                    packId,
                    packTitle,
                    intent.getBooleanExtra(KEY_PUSH_TELEGRAM, false),
                    intent.getBooleanExtra(KEY_ADD_WHATSAPP, false),
                )
                else -> null
            }
        }
    }
}
