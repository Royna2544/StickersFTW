package com.royna.stickersftw.data

import android.content.Context
import androidx.core.content.edit
import com.royna.stickersftw.model.ThemeMode

/** A synchronous mirror of the theme preference that [SettingsRepository]
 * owns in DataStore.
 *
 * DataStore reads are asynchronous, but two things need an answer before any
 * suspend function has had a chance to run. The activity has to pick its
 * window theme before `super.onCreate` -- that window is what's on screen
 * from process start until Compose draws its first frame -- and
 * [com.royna.stickersftw.ui.AppViewModel] has to seed its settings
 * StateFlow with something for that first frame. Both used to fall back on
 * the *system* dark-mode setting, which is why a cold start flashed dark
 * before settling on a forced-Light theme.
 *
 * DataStore stays the source of truth; this is only ever written through
 * [SettingsRepository.setThemeMode], alongside the real write. */
object ThemeModeCache {
    private const val PREFS_NAME = "stickers_ftw_theme_cache"
    private const val KEY_THEME_MODE = "theme_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): ThemeMode {
        val stored = prefs(context).getString(KEY_THEME_MODE, null) ?: return ThemeMode.System
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    fun write(context: Context, mode: ThemeMode) {
        prefs(context).edit { putString(KEY_THEME_MODE, mode.name) }
    }
}
