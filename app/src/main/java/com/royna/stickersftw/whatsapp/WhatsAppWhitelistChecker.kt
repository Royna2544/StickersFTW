package com.royna.stickersftw.whatsapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Queries WhatsApp's (or WhatsApp Business's) own "is this pack already
 * added?" provider for a real answer instead of a locally-guessed flag.
 * Returns null -- not false -- when the provider is absent/unreachable, so
 * callers can distinguish "known not added" from "unknown". */
object WhatsAppWhitelistChecker {
    suspend fun isWhitelisted(
        context: Context,
        authority: String,
        identifier: String,
        business: Boolean,
    ): Boolean? = withContext(Dispatchers.IO) {
        val providerAuthority = if (business) {
            "com.whatsapp.w4b.provider.sticker_whitelist_check"
        } else {
            "com.whatsapp.provider.sticker_whitelist_check"
        }
        val uri = Uri.parse("content://$providerAuthority/is_whitelisted")
            .buildUpon()
            .appendQueryParameter("authority", authority)
            .appendQueryParameter("identifier", identifier)
            .build()

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    val columnIndex = cursor.getColumnIndex("result")
                    if (columnIndex >= 0) cursor.getInt(columnIndex) == 1 else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
