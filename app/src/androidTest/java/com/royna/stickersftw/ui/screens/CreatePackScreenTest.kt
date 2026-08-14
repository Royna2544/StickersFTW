package com.royna.stickersftw.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.ui.CreatePackSubmissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class CreatePackScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun acceptedSubmissionFreezesScreenAndAsyncFailureUnlocksIt() {
        var completeSubmission: ((CreatePackSubmissionResult) -> Unit)? = null
        composeRule.setContent {
            MaterialTheme {
                CreatePackScreen(
                    onBack = {},
                    initialItems = preparedItems(3),
                    onPublish = { _, _, _, _, _, onResult ->
                        completeSubmission = onResult
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.create_pack_title_label))
            .performTextInput("Pack")
        composeRule.onNodeWithText(context.getString(R.string.create_pack_short_name_label))
            .performTextInput("pack")
        val publish = composeRule.onNodeWithText(context.getString(R.string.action_create_and_publish))
            .performScrollTo()

        publish.assertIsEnabled().performClick().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back))
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            assertNotNull(completeSubmission)
            completeSubmission?.invoke(CreatePackSubmissionResult.Failed)
        }
        publish.assertIsEnabled()
    }

    @Test
    fun removingPreparedItemDiscardsThatExactItem() {
        val item = preparedItems(1).single()
        val discarded = mutableListOf<PickedMediaItem>()
        composeRule.setContent {
            MaterialTheme {
                CreatePackScreen(
                    onBack = {},
                    initialItems = listOf(item),
                    onDiscardMedia = discarded::addAll,
                    onPublish = { _, _, _, _, _, _ -> false },
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_remove))
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(item), discarded) }
    }

    private fun preparedItems(count: Int): List<PickedMediaItem> =
        (0 until count).map { index ->
            PickedMediaItem("file:///missing/create-$index.png", PickedMediaKind.Image)
        }
}
