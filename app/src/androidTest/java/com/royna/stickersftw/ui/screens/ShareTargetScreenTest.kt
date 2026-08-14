package com.royna.stickersftw.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.StickerPack
import org.junit.Rule
import org.junit.Test

class ShareTargetScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun preparationInFlightDisablesNewAndExistingTargets() {
        composeRule.setContent {
            MaterialTheme {
                ShareTargetScreen(
                    packs = listOf(
                        StickerPack(
                            id = "pack",
                            title = "Existing pack",
                            author = "Author",
                            origin = PackOrigin.Created,
                            stickerCount = 3,
                            isAnimated = false,
                            status = PackStatus.Ready,
                        ),
                    ),
                    sharedCount = 1,
                    enabled = false,
                    onCreateNew = {},
                    onAddToPack = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.share_target_new_pack))
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Existing pack").assertIsNotEnabled()
    }
}
