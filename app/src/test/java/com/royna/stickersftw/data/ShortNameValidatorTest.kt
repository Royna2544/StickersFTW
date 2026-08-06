package com.royna.stickersftw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortNameValidatorTest {

    @Test
    fun `plain valid name is accepted as-is`() {
        val result = ShortNameValidator.validate("my_pack", botUsername = "ftw_downloader_bot")
        assertEquals(ShortNameValidator.Result.Valid("my_pack"), result)
    }

    @Test
    fun `name starting with digit is rejected`() {
        val result = ShortNameValidator.validate("1pack", botUsername = "ftw_downloader_bot")
        assertTrue(result is ShortNameValidator.Result.InvalidFormat)
    }

    @Test
    fun `name with illegal characters is rejected`() {
        val result = ShortNameValidator.validate("my-pack!", botUsername = "ftw_downloader_bot")
        assertTrue(result is ShortNameValidator.Result.InvalidFormat)
    }

    @Test
    fun `own bot suffix is stripped`() {
        val result = ShortNameValidator.validate("my_pack_by_ftw_downloader_bot", botUsername = "ftw_downloader_bot")
        assertEquals(ShortNameValidator.Result.Valid("my_pack"), result)
    }

    @Test
    fun `own bot suffix is stripped case-insensitively`() {
        val result = ShortNameValidator.validate("my_pack_by_FTW_Downloader_Bot", botUsername = "ftw_downloader_bot")
        assertEquals(ShortNameValidator.Result.Valid("my_pack"), result)
    }

    @Test
    fun `foreign bot suffix is rejected`() {
        val result = ShortNameValidator.validate("my_pack_by_moe_sticker_bot", botUsername = "ftw_downloader_bot")
        assertEquals(ShortNameValidator.Result.WrongBotSuffix("moe_sticker_bot"), result)
    }

    @Test
    fun `suffix present but bot username unknown is rejected`() {
        val result = ShortNameValidator.validate("my_pack_by_moe_sticker_bot", botUsername = null)
        assertTrue(result is ShortNameValidator.Result.WrongBotSuffix)
    }

    @Test
    fun `blank name is rejected`() {
        val result = ShortNameValidator.validate("   ", botUsername = "ftw_downloader_bot")
        assertTrue(result is ShortNameValidator.Result.InvalidFormat)
    }
}
