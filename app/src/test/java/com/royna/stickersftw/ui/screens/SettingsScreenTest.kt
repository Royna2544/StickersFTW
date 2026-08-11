package com.royna.stickersftw.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun telegramStartUrlAddsOnboardingPayload() {
        assertEquals(
            "https://t.me/StickersFTWBot?start=ftw_connect_v1",
            telegramStartUrl("StickersFTWBot"),
        )
    }

    @Test
    fun telegramStartUrlAcceptsDisplayUsername() {
        assertEquals(
            "https://t.me/StickersFTWBot?start=ftw_connect_v1",
            telegramStartUrl("@StickersFTWBot"),
        )
    }
}
