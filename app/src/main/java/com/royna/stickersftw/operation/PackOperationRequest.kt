package com.royna.stickersftw.operation

import android.content.Intent
import com.royna.stickersftw.model.MediaCrop
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind

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

    /** Appends already-picked local media to an existing pack. The items are
     * carried as parallel arrays because an Intent has no way to hold a list
     * of PickedMediaItem without making it Parcelable, and all of its fields
     * are primitives. */
    data class AddStickers(
        override val packId: String,
        override val packTitle: String,
        val items: List<PickedMediaItem>,
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
            is AddStickers -> {
                putExtra(KEY_KIND, KIND_ADD_STICKERS)
                putExtra(KEY_ITEM_URIS, items.map { it.uri }.toTypedArray())
                putExtra(KEY_ITEM_EMOJIS, items.map { it.emoji }.toTypedArray())
                putExtra(KEY_ITEM_IS_VIDEO, items.map { it.kind == PickedMediaKind.Video }.toBooleanArray())
                putExtra(KEY_ITEM_TRIM_STARTS, items.map { it.trimStartMs }.toLongArray())
                putExtra(KEY_ITEM_HAS_CROP, items.map { it.crop != null }.toBooleanArray())
                putExtra(KEY_ITEM_CROP_LEFTS, items.map { it.crop?.left ?: 0f }.toFloatArray())
                putExtra(KEY_ITEM_CROP_TOPS, items.map { it.crop?.top ?: 0f }.toFloatArray())
                putExtra(KEY_ITEM_CROP_RIGHTS, items.map { it.crop?.right ?: 1f }.toFloatArray())
                putExtra(KEY_ITEM_CROP_BOTTOMS, items.map { it.crop?.bottom ?: 1f }.toFloatArray())
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
        private const val KEY_ITEM_URIS = "itemUris"
        private const val KEY_ITEM_EMOJIS = "itemEmojis"
        private const val KEY_ITEM_IS_VIDEO = "itemIsVideo"
        private const val KEY_ITEM_TRIM_STARTS = "itemTrimStarts"
        private const val KEY_ITEM_HAS_CROP = "itemHasCrop"
        private const val KEY_ITEM_CROP_LEFTS = "itemCropLefts"
        private const val KEY_ITEM_CROP_TOPS = "itemCropTops"
        private const val KEY_ITEM_CROP_RIGHTS = "itemCropRights"
        private const val KEY_ITEM_CROP_BOTTOMS = "itemCropBottoms"

        private const val KIND_IMPORT = "import"
        private const val KIND_IMPORT_CUSTOM = "importCustom"
        private const val KIND_UPDATE = "update"
        private const val KIND_PUBLISH = "publish"
        private const val KIND_ADD_STICKERS = "addStickers"

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
                KIND_ADD_STICKERS -> {
                    val uris = intent.getStringArrayExtra(KEY_ITEM_URIS).orEmpty()
                    val emojis = intent.getStringArrayExtra(KEY_ITEM_EMOJIS).orEmpty()
                    val isVideo = intent.getBooleanArrayExtra(KEY_ITEM_IS_VIDEO) ?: BooleanArray(uris.size)
                    val trimStarts = intent.getLongArrayExtra(KEY_ITEM_TRIM_STARTS) ?: LongArray(uris.size)
                    val hasCrop = intent.getBooleanArrayExtra(KEY_ITEM_HAS_CROP) ?: BooleanArray(uris.size)
                    val cropLefts = intent.getFloatArrayExtra(KEY_ITEM_CROP_LEFTS) ?: FloatArray(uris.size)
                    val cropTops = intent.getFloatArrayExtra(KEY_ITEM_CROP_TOPS) ?: FloatArray(uris.size)
                    val cropRights = intent.getFloatArrayExtra(KEY_ITEM_CROP_RIGHTS) ?: FloatArray(uris.size) { 1f }
                    val cropBottoms = intent.getFloatArrayExtra(KEY_ITEM_CROP_BOTTOMS) ?: FloatArray(uris.size) { 1f }
                    AddStickers(
                        packId,
                        packTitle,
                        uris.mapIndexed { index, uri ->
                            PickedMediaItem(
                                uri = uri,
                                kind = if (isVideo.getOrElse(index) { false }) {
                                    PickedMediaKind.Video
                                } else {
                                    PickedMediaKind.Image
                                },
                                emoji = emojis.getOrElse(index) { "🙂" },
                                trimStartMs = trimStarts.getOrElse(index) { 0L },
                                crop = if (hasCrop.getOrElse(index) { false }) {
                                    MediaCrop(
                                        left = cropLefts.getOrElse(index) { 0f },
                                        top = cropTops.getOrElse(index) { 0f },
                                        right = cropRights.getOrElse(index) { 1f },
                                        bottom = cropBottoms.getOrElse(index) { 1f },
                                    )
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
                else -> null
            }
        }
    }
}
