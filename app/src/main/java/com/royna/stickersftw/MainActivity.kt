package com.royna.stickersftw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.royna.stickersftw.ui.AppViewModel
import com.royna.stickersftw.ui.theme.StickersFtwTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            StickersFtwTheme(themeMode = settings.themeMode) {
                StickersFtwApp(viewModel = viewModel)
            }
        }
    }
}
