package com.royna.stickersftw.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.R
import com.royna.stickersftw.model.StickerGridItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StickerGridScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lottieVisualEditingAndDeletingAtMinimumAreDisabled() {
        composeRule.setContent {
            MaterialTheme {
                StickerGridScreen(
                    packTitle = "Pack",
                    stickers = stickers(3).mapIndexed { index, item ->
                        if (index == 0) item.copy(canEditVisual = false) else item
                    },
                    onBack = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("${context.getString(R.string.sticker_editor_sticker)} 1")
            .performClick()

        composeRule.onNodeWithText(context.getString(R.string.sticker_editor_edit_visual))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.sticker_editor_visual_unavailable))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sticker_editor_delete_minimum))
            .assertIsDisplayed()
    }

    @Test
    fun accessibilityMoveActionCommitsTheNewStableIdOrder() {
        var committed: List<Long>? = null
        composeRule.setContent {
            MaterialTheme {
                StickerGridScreen(
                    packTitle = "Pack",
                    stickers = stickers(4),
                    onBack = {},
                    onReorder = { order ->
                        committed = order
                        true
                    },
                )
            }
        }

        val actions = composeRule
            .onNodeWithContentDescription("${context.getString(R.string.sticker_editor_sticker)} 2")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeRule.runOnIdle {
            val moveEarlier = actions.first {
                it.label == context.getString(R.string.sticker_editor_move_earlier)
            }
            assertTrue(moveEarlier.action())
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            committed == listOf(2L, 1L, 3L, 4L)
        }
    }

    private fun stickers(count: Int): List<StickerGridItem> =
        (1..count).map { value ->
            StickerGridItem(
                rowId = value.toLong(),
                position = value - 1,
                path = "/missing/$value.webp",
                emoji = "🙂",
                isVideo = false,
                isTray = value == 1,
                canEditVisual = true,
            )
        }
}
