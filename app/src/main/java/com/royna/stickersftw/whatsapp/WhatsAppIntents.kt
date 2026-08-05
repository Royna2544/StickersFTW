package com.royna.stickersftw.whatsapp

import android.content.Intent

object WhatsAppIntents {
    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun buildAddPackIntent(
        authority: String,
        packId: String,
        packTitle: String,
        targetPackage: String,
    ): Intent = Intent(ACTION_ENABLE_STICKER_PACK).apply {
        setPackage(targetPackage)
        putExtra("sticker_pack_id", packId)
        putExtra("sticker_pack_authority", authority)
        putExtra("sticker_pack_name", packTitle)
    }
}
