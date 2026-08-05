package com.royna.stickersftw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.royna.stickersftw.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = LavenderPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = LavenderContainer,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF251342),
    secondary = TelegramBlue,
    tertiary = WhatsAppGreen,
    background = SoftBackground,
    onBackground = androidx.compose.ui.graphics.Color(0xFF211A22),
    surface = SoftSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFF211A22),
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF504852),
    outline = SoftOutline,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = LavenderPrimaryDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF3B2365),
    primaryContainer = LavenderContainerDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE9DDFF),
    secondary = androidx.compose.ui.graphics.Color(0xFF79C7F2),
    tertiary = androidx.compose.ui.graphics.Color(0xFF6CDF8E),
    background = DarkBackground,
    onBackground = androidx.compose.ui.graphics.Color(0xFFF0E8F1),
    surface = DarkSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFFF0E8F1),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFD5CCD8),
    outline = androidx.compose.ui.graphics.Color(0xFF9A909D),
)

@Composable
fun StickersFtwTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
