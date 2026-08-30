package com.royna.stickersftw.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemixPackDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun defaultsToOriginalTitleWithRemixSuffix() {
        showDialog()

        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("Original (Remix)"),
            ),
        )
    }

    @Test
    fun blankTitleShowsValidationAndDisablesConfirm() {
        showDialog()

        composeRule.onNode(hasSetTextAction()).performTextReplacement("   ")

        composeRule.onNodeWithText(context.getString(R.string.remix_pack_name_required))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.remix_pack_confirm))
            .assertIsNotEnabled()
    }

    @Test
    fun cancelInvokesCancelCallback() {
        var cancelled = false
        showDialog(onCancel = { cancelled = true })

        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun confirmReturnsEditedTrimmedTitle() {
        var confirmed: String? = null
        showDialog(onConfirm = { confirmed = it })
        composeRule.onNode(hasSetTextAction()).performTextReplacement("  My remix  ")

        composeRule.onNodeWithText(context.getString(R.string.remix_pack_confirm)).performClick()

        composeRule.runOnIdle { assertEquals("My remix", confirmed) }
    }

    @Test
    fun creatingStateBlocksDismissAndFurtherConfirmation() {
        var cancelled = false
        showDialog(isCreating = true, onCancel = { cancelled = true })

        composeRule.onNodeWithText(context.getString(R.string.remix_pack_creating))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.remix_pack_confirm))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel))
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(!cancelled) }
    }

    private fun showDialog(
        isCreating: Boolean = false,
        onConfirm: (String) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                RemixPackDialog(
                    packTitle = "Original",
                    isCreating = isCreating,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )
            }
        }
    }
}
