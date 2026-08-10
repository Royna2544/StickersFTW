package com.royna.stickersftw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.royna.stickersftw.model.AppSettings
import com.royna.stickersftw.model.BackendMode
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
        val PingTestsEnabled = booleanPreferencesKey("ping_tests_enabled")
        val BackendMode = stringPreferencesKey("backend_mode")
        val ConversionBias = stringPreferencesKey("conversion_bias")
    }

    private val secureTokenStore = SecureTokenStore(context)

    /** The bot token lives in [SecureTokenStore] (encrypted), not DataStore
     * -- this keeps [settings] reactive to token saves the same way it's
     * reactive to every other DataStore-backed write. */
    private val botTokenFlow = MutableStateFlow(secureTokenStore.getBotToken())

    private val dataStoreFlow = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw throwable
            }
        }

    val settings: Flow<AppSettings> = combine(dataStoreFlow, botTokenFlow) { preferences, botToken ->
        AppSettings(
            serverUrl = preferences[Keys.ServerUrl] ?: "http://10.0.2.2:8080",
            themeMode = preferences[Keys.ThemeMode]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: ThemeMode.System,
            telegramUserId = preferences[Keys.TelegramUserId] ?: "",
            updateChecksEnabled = preferences[Keys.UpdateChecksEnabled] ?: true,
            pingTestsEnabled = preferences[Keys.PingTestsEnabled] ?: true,
            backendMode = preferences[Keys.BackendMode]
                ?.let { stored -> BackendMode.entries.firstOrNull { it.name == stored } }
                ?: BackendMode.ServerUrl,
            conversionBias = preferences[Keys.ConversionBias]
                ?.let { stored -> ConversionBias.entries.firstOrNull { it.name == stored } }
                ?: ConversionBias.Auto,
            botToken = botToken,
        )
    }

    suspend fun setConversionBias(bias: ConversionBias) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.ConversionBias] = bias.name
        }
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.ServerUrl] = url.trim().trimEnd('/')
        }
    }

    suspend fun setBackendMode(mode: BackendMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.BackendMode] = mode.name
        }
    }

    fun setBotToken(token: String) {
        secureTokenStore.setBotToken(token)
        botTokenFlow.value = token.trim()
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        // Mirrored into [ThemeModeCache] so the next cold start can choose a
        // window theme before DataStore is readable. DataStore remains the
        // source of truth; the cache is only ever written here.
        ThemeModeCache.write(context, mode)
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

    suspend fun setPingTestsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PingTestsEnabled] = enabled
        }
    }
}
