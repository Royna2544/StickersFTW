package com.royna.stickersftw

import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.royna.stickersftw.data.ThemeModeCache
import com.royna.stickersftw.model.ThemeMode
import androidx.lifecycle.lifecycleScope
import com.royna.stickersftw.model.PickedMediaItem
import kotlinx.coroutines.launch
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.theme.StickersFtwTheme
import com.royna.stickersftw.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var pendingPackId by mutableStateOf<String?>(null)
    private var sharedMedia by mutableStateOf<List<PickedMediaItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        // The window exists from process start until Compose draws its first
        // frame, and Android resolves its theme through the -night resource
        // qualifier -- that is, from the *system* setting, which is the wrong
        // answer for anyone who forced Light or Dark in Settings. Reading the
        // synchronously-available preference and calling setTheme() before
        // the window is created is what keeps a cold start from flashing the
        // other theme.
        val startupThemeMode = ThemeModeCache.read(this)
        setTheme(
            when (startupThemeMode) {
                ThemeMode.System -> R.style.Theme_StickersFTW
                ThemeMode.Light -> R.style.Base_Theme_StickersFTW_Light
                ThemeMode.Dark -> R.style.Base_Theme_StickersFTW_Dark
            },
        )
        // Has to happen here rather than only from the composition below:
        // enableEdgeToEdge also turns off decor fitting, so deferring it by a
        // frame would lay the first frame out inside the system bars and then
        // shift it.
        applyEdgeToEdge(startupThemeMode.resolveDarkTheme(resources))
        super.onCreate(savedInstanceState)
        pendingPackId = intent?.getStringExtra(EXTRA_PACK_ID)
        ingestShare(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = settings.themeMode.resolveDarkTheme()

            // Keeps the bar icons in step once the real preference lands from
            // DataStore, and when the user flips the setting. The first run is
            // a no-op repeat of the onCreate call above.
            LaunchedEffect(darkTheme) { applyEdgeToEdge(darkTheme) }

            StickersFtwTheme(themeMode = settings.themeMode) {
                StickersFtwApp(
                    viewModel = viewModel,
                    pendingPackId = pendingPackId,
                    onPendingPackIdConsumed = { pendingPackId = null },
                    sharedMedia = sharedMedia,
                    onSharedMediaConsumed = { sharedMedia = emptyList() },
                )
            }
        }
    }

    // singleTask launch mode routes a notification tap back into this same
    // instance instead of creating a new one, so the pending pack id has to
    // be picked up here rather than only in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingPackId = intent.getStringExtra(EXTRA_PACK_ID)
        // singleTask means a share into an already-running instance arrives
        // here rather than at onCreate, so it has to be read in both places.
        ingestShare(intent)
    }

    /** Copies shared media off the incoming grant before anything else can
     * need it -- see [SharedMedia]. Off the main thread because a shared clip
     * can be tens of megabytes. */
    private fun ingestShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        lifecycleScope.launch {
            sharedMedia = SharedMedia.ingest(intent, this@MainActivity)
        }
    }

    /** Bare [enableEdgeToEdge] detects dark mode from `Configuration.uiMode`,
     * which leaves the system bar icons tinted for the system's theme even
     * when the app is forced to the opposite one -- white icons on the light
     * background, in the case that prompted this. Passing [darkTheme] in
     * explicitly is the whole point; the scrims are androidx's own defaults,
     * kept so nothing else about the previous behaviour changes. */
    private fun applyEdgeToEdge(darkTheme: Boolean) {
        val detectDarkMode = { _: Resources -> darkTheme }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                detectDarkMode,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                DEFAULT_LIGHT_SCRIM,
                DEFAULT_DARK_SCRIM,
                detectDarkMode,
            ),
        )
    }

    companion object {
        const val EXTRA_PACK_ID = "packId"

        // androidx.activity's own DefaultLightScrim/DefaultDarkScrim, which
        // are internal and so can't be referenced directly.
        private val DEFAULT_LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DEFAULT_DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
