package com.royna.stickersftw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.theme.StickersFtwTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var pendingPackId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingPackId = intent?.getStringExtra(EXTRA_PACK_ID)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            StickersFtwTheme(themeMode = settings.themeMode) {
                StickersFtwApp(
                    viewModel = viewModel,
                    pendingPackId = pendingPackId,
                    onPendingPackIdConsumed = { pendingPackId = null },
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
    }

    companion object {
        const val EXTRA_PACK_ID = "packId"
    }
}
