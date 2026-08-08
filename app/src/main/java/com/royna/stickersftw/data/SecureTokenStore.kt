package com.royna.stickersftw.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Holds the user's Telegram bot token -- a real credential, unlike the
 * other plain-DataStore settings in [SettingsRepository] -- encrypted at
 * rest via Jetpack Security (AES256-GCM values, AES256-SIV keys, backed by
 * a Keystore-generated [MasterKey]). [EncryptedSharedPreferences] keeps an
 * in-memory copy after first load, so these reads/writes are cheap enough
 * to call synchronously like every other `settings.value.xxx` read in this
 * app. */
class SecureTokenStore(context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getBotToken(): String = prefs.getString(KEY_BOT_TOKEN, null) ?: ""

    fun setBotToken(token: String) {
        prefs.edit().putString(KEY_BOT_TOKEN, token.trim()).apply()
    }

    private companion object {
        const val FILE_NAME = "stickers_ftw_secure"
        const val KEY_BOT_TOKEN = "bot_token"
    }
}
