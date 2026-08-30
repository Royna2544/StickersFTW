package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.royna.stickersftw.ui.components.ReimportUpdatedPackDialog
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

    @Test
    fun legacyImportedPackShowsVersionAndReconversionOffer() {
        var requestedPackId: String? = null
        showDetail(
            pack = pack(
                origin = PackOrigin.Imported,
                needsReconversion = true,
            ),
            onReconvert = { requestedPackId = it },
        )

        composeRule.onNodeWithText(
            context.getString(
                R.string.pack_detail_converted_app_version,
                context.getString(R.string.pack_detail_converted_app_version_unknown),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_reconvert_pack))
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals("pack", requestedPackId) }
    }

    @Test
    fun reconversionPreflightDisablesActionAndShowsProgress() {
        showDetail(
            pack = pack(
                origin = PackOrigin.Imported,
                needsReconversion = true,
            ),
            reconversionCheckInProgress = true,
        )

        composeRule.onNodeWithText(context.getString(R.string.reconversion_checking_telegram))
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun currentImportedPackShowsVersionWithoutReconversionOffer() {
        showDetail(
            pack = pack(
                origin = PackOrigin.Imported,
                convertedAppVersionCode = 3,
                convertedAppVersionName = "1.1",
                needsReconversion = false,
            ),
        )

        composeRule.onNodeWithText(
            context.getString(R.string.pack_detail_converted_app_version, "1.1"),
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.action_reconvert_pack))
            .assertCountEquals(0)
    }

    @Test
    fun updatedTelegramPromptUsesExactQuestionAndYesAction() {
        var yes = 0
        composeRule.setContent {
            MaterialTheme {
                ReimportUpdatedPackDialog(
                    packTitle = "Fresh pack",
                    onYes = { yes += 1 },
                    onNo = {},
                )
            }
        }

        composeRule.onNodeWithText("The pack is updated on Telegram, reimport anyway?")
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_yes)).performClick()

        composeRule.runOnIdle { assertEquals(1, yes) }
    }

    @Test
    fun updatedTelegramPromptNoActionDeclinesReimport() {
        var no = 0
        composeRule.setContent {
            MaterialTheme {
                ReimportUpdatedPackDialog(
                    packTitle = "Fresh pack",
                    onYes = {},
                    onNo = { no += 1 },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.action_no)).performClick()

        composeRule.runOnIdle { assertEquals(1, no) }
    }

    private fun showDetail(
        pack: StickerPack,
        onBuildWhatsappIntent: () -> android.content.Intent? = { null },
        onPushToTelegram: (String) -> Unit = {},
        reconversionCheckInProgress: Boolean = false,
        onReconvert: (String) -> Unit = {},
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
                    reconversionCheckInProgress = reconversionCheckInProgress,
                    onReconvert = onReconvert,
                )
            }
        }
    }

    private fun pack(
        whatsapp: WhatsappFreshnessState = WhatsappFreshnessState.NotAdded,
        telegram: TelegramFreshnessState = TelegramFreshnessState.NotPushed,
        origin: PackOrigin = PackOrigin.Created,
        convertedAppVersionCode: Int? = null,
        convertedAppVersionName: String? = null,
        needsReconversion: Boolean = false,
    ): StickerPack = StickerPack(
        id = "pack",
        title = "Fresh pack",
        author = "Author",
        origin = origin,
        stickerCount = 3,
        isAnimated = false,
        status = PackStatus.Ready,
        whatsappFreshness = whatsapp,
        telegramFreshness = telegram,
        convertedAppVersionCode = convertedAppVersionCode,
        convertedAppVersionName = convertedAppVersionName,
        needsReconversion = needsReconversion,
    )
}
