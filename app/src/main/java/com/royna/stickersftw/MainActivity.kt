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
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.theme.StickersFtwTheme
import com.royna.stickersftw.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var pendingPackId by mutableStateOf<String?>(null)

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
        // The ViewModel claims an initial SEND only once. It survives a
        // configuration replacement along with the exact batch instance (or
        // an in-flight copy), so recreation must not copy the retained Intent
        // into a second directory and replace the editor underneath the user.
        viewModel.ingestInitialSharedMedia(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val sharedMedia by viewModel.sharedMedia.collectAsStateWithLifecycle()
            val sharedDeliveryActive by viewModel.sharedDeliveryActive.collectAsStateWithLifecycle()
            val darkTheme = settings.themeMode.resolveDarkTheme()

            // Keeps the bar icons in step once the real preference lands from
            // DataStore, and when the user flips the setting. The first run is
            // a no-op repeat of the onCreate call above.
            LaunchedEffect(darkTheme) { applyEdgeToEdge(darkTheme) }
            // A retained callback from the Activity being replaced may be the
            // one that consumes the batch. Observe ownership here as well so
            // the current Activity, not only that old instance, clears SEND.
            LaunchedEffect(sharedDeliveryActive) {
                if (!sharedDeliveryActive) clearRetainedShareIntent()
            }

            StickersFtwTheme(themeMode = settings.themeMode) {
                StickersFtwApp(
                    viewModel = viewModel,
                    pendingPackId = pendingPackId,
                    onPendingPackIdConsumed = {
                        pendingPackId = null
                        clearRetainedPackId()
                    },
                    sharedMedia = sharedMedia,
                    onSharedMediaConsumed = { consumed ->
                        // Only consuming the exact current batch may clear the
                        // retained SEND. A late callback from an older editor,
                        // or consumption while a newer share is still copying,
                        // must leave that newer delivery reconstructible.
                        if (viewModel.consumeSharedMedia(consumed)) {
                            clearRetainedShareIntent()
                        }
                    },
                )
            }
        }
    }

    /** Prevents a consumed notification destination from replaying after an
     * Activity recreation. Copying the Intent and removing only this extra
     * deliberately preserves an active SEND action, grant, ClipData, MIME
     * type, and stream while shared media is still being edited. */
    private fun clearRetainedPackId() {
        val retained = intent ?: return
        if (!retained.hasExtra(EXTRA_PACK_ID)) return
        setIntent(retained.copyWithoutPendingPackId())
    }

    // singleTask launch mode routes a notification tap back into this same
    // instance instead of creating a new one, so the pending pack id has to
    // be picked up here rather than only in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ComponentActivity does not replace getIntent() for us. Keeping the
        // latest singleTask delivery here is essential if a configuration
        // change happens while its media is still being copied.
        setIntent(intent)
        pendingPackId = intent.getStringExtra(EXTRA_PACK_ID)
        // singleTask means a share into an already-running instance arrives
        // here rather than at onCreate, so it has to be read in both places.
        viewModel.replaceSharedMedia(intent)
    }

    /** Stops a consumed SEND from being replayed by Android when this Activity
     * is recreated. Other launch metadata is preserved. */
    private fun clearRetainedShareIntent() {
        val retained = intent ?: return
        if (retained.action != Intent.ACTION_SEND && retained.action != Intent.ACTION_SEND_MULTIPLE) return
        setIntent(
            Intent(retained).apply {
                action = Intent.ACTION_MAIN
                type = null
                clipData = null
                removeExtra(Intent.EXTRA_STREAM)
            },
        )
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

internal fun Intent.copyWithoutPendingPackId(): Intent =
    Intent(this).apply { removeExtra(MainActivity.EXTRA_PACK_ID) }
