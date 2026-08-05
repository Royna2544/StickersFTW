package com.royna.stickersftw.whatsapp

import android.content.Context

/** Column-name constants copied verbatim from WhatsApp's own official sample
 * (github.com/WhatsApp/stickers) -- WhatsApp validates these strictly and a
 * typo silently breaks recognition with no error surfaced. */
object WhatsAppContract {
    private const val AUTHORITY_SUFFIX = ".stickercontentprovider"

    fun authorityFor(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    const val TRAY_ICON_FILE_NAME = "tray.webp"

    object Metadata {
        const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        const val STICKER_PACK_NAME = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        const val STICKER_PACK_ICON = "sticker_pack_icon"
        const val ANDROID_PLAY_STORE_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        val ALL_COLUMNS = arrayOf(
            STICKER_PACK_IDENTIFIER,
            STICKER_PACK_NAME,
            STICKER_PACK_PUBLISHER,
            STICKER_PACK_ICON,
            ANDROID_PLAY_STORE_LINK,
            IOS_APP_DOWNLOAD_LINK,
            PUBLISHER_EMAIL,
            PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE,
            LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION,
            AVOID_CACHE,
            ANIMATED_STICKER_PACK,
        )
    }

    object Stickers {
        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_EMOJI = "sticker_emoji"
        const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"

        val ALL_COLUMNS = arrayOf(STICKER_FILE_NAME, STICKER_EMOJI, STICKER_ACCESSIBILITY_TEXT)
    }
}
