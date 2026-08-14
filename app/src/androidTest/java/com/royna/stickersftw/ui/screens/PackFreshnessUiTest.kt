package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.royna.stickersftw.R
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramFreshnessState
import com.royna.stickersftw.model.WhatsappFreshnessState
import com.royna.stickersftw.ui.components.TelegramFreshnessBadge
import com.royna.stickersftw.ui.components.WhatsappFreshnessBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PackFreshnessUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun badgesRenderEveryWhatsappAndTelegramState() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    WhatsappFreshnessState.entries.forEach { WhatsappFreshnessBadge(it) }
                    TelegramFreshnessState.entries.forEach { TelegramFreshnessBadge(it) }
                }
            }
        }

        listOf(
            R.string.freshness_whatsapp_not_added,
            R.string.freshness_whatsapp_current,
            R.string.freshness_whatsapp_needs_refresh,
            R.string.freshness_telegram_not_pushed,
            R.string.freshness_telegram_partial,
            R.string.freshness_telegram_current,
            R.string.freshness_telegram_out_of_date,
        ).forEach { label ->
            composeRule.onNodeWithText(context.getString(label)).assertIsDisplayed()
        }
    }

    @Test
    fun needsRefreshUsesTheExistingWhatsappIntentFlow() {
        var intentBuilds = 0
        showDetail(
            pack = pack(whatsapp = WhatsappFreshnessState.NeedsRefresh),
            onBuildWhatsappIntent = {
                intentBuilds += 1
                null
            },
        )

        composeRule.onNodeWithText(context.getString(R.string.action_refresh_whatsapp_pack))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(1, intentBuilds) }
    }

    @Test
    fun partialTelegramOffersFinishPush() {
        var pushed = false
        showDetail(
            pack = pack(telegram = TelegramFreshnessState.Partial),
            onPushToTelegram = { pushed = true },
        )

        composeRule.onNodeWithText(context.getString(R.string.action_finish_push_to_telegram))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertTrue(pushed) }
    }

    @Test
    fun outOfDateTelegramIsDisplayOnlyWithNoPushOrUpdateAction() {
        showDetail(pack = pack(telegram = TelegramFreshnessState.OutOfDate))

        composeRule.onNodeWithText(context.getString(R.string.freshness_telegram_out_of_date))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.action_push_to_telegram))
            .assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.action_finish_push_to_telegram))
            .assertCountEquals(0)
    }

    @Test
    fun myPacksCardShowsBothTargetStates() {
        val item = pack(
            whatsapp = WhatsappFreshnessState.NeedsRefresh,
            telegram = TelegramFreshnessState.OutOfDate,
        )
        composeRule.setContent {
            MaterialTheme {
                MyPacksScreen(
                    packs = listOf(item),
                    onOpenPack = {},
                    onTogglePinned = {},
                    onDeletePack = {},
                    isRefreshing = false,
                    onRefresh = {},
                    onRequestUpdate = {},
                    onDisableUpdates = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.freshness_whatsapp_needs_refresh))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.freshness_telegram_out_of_date))
            .assertIsDisplayed()
    }

    private fun showDetail(
        pack: StickerPack,
        onBuildWhatsappIntent: () -> android.content.Intent? = { null },
        onPushToTelegram: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PackDetailScreen(
                    pack = pack,
                    whatsappAvailable = true,
                    whatsappBusiness = false,
                    onBack = {},
                    onTogglePinned = {},
                    onDelete = {},
                    onBuildWhatsappIntent = onBuildWhatsappIntent,
                    onWhatsappResult = { _, _, _ -> },
                    onRefreshWhatsapp = {},
                    onPushToTelegram = onPushToTelegram,
                )
            }
        }
    }

    private fun pack(
        whatsapp: WhatsappFreshnessState = WhatsappFreshnessState.NotAdded,
        telegram: TelegramFreshnessState = TelegramFreshnessState.NotPushed,
    ): StickerPack = StickerPack(
        id = "pack",
        title = "Fresh pack",
        author = "Author",
        origin = PackOrigin.Created,
        stickerCount = 3,
        isAnimated = false,
        status = PackStatus.Ready,
        whatsappFreshness = whatsapp,
        telegramFreshness = telegram,
    )
}
