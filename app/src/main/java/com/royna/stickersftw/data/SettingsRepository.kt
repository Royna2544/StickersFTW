package com.royna.stickersftw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "stickers_ftw_settings")

class SettingsRepository(
    private val context: Context,
) {
    private object Keys {
        val ServerUrl = stringPreferencesKey("server_url")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val TelegramUserId = stringPreferencesKey("telegram_user_id")
        val UpdateChecksEnabled = booleanPreferencesKey("update_checks_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            AppSettings(
                serverUrl = preferences[Keys.ServerUrl] ?: "http://10.0.2.2:8080",
                themeMode = preferences[Keys.ThemeMode]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.System,
                telegramUserId = preferences[Keys.TelegramUserId] ?: "",
                updateChecksEnabled = preferences[Keys.UpdateChecksEnabled] ?: true,
            )
        }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.ServerUrl] = url.trim().trimEnd('/')
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.ThemeMode] = mode.name
        }
    }

    suspend fun setTelegramUserId(userId: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.TelegramUserId] = userId.trim().filter { it.isDigit() }
        }
    }

    suspend fun setUpdateChecksEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.UpdateChecksEnabled] = enabled
        }
    }
}
