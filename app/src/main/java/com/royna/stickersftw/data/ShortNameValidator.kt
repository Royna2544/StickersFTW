package com.royna.stickersftw.data

/** Validates/normalizes a user-entered Telegram sticker set short name before
 * it's persisted or pushed. The server always appends "_by_<bot_username>"
 * itself (see StickersFTW_BotServer.cpp's sanitizeShortName/pushSticker), so
 * a short name the user types must be the bare base name -- but users often
 * paste a name copied from an existing Telegram set link, which already
 * carries a "_by_<bot>" suffix. This strips that suffix when it's ours, and
 * rejects it outright when it names a different bot (passing it through
 * would silently create/target a set under the wrong bot's namespace). */
object ShortNameValidator {
    private val BASE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private const val SUFFIX_MARKER = "_by_"

    sealed class Result {
        data class Valid(val baseName: String) : Result()
        data object InvalidFormat : Result()
        data class WrongBotSuffix(val suffixBot: String) : Result()
    }

    fun validate(raw: String, botUsername: String?): Result {
        val trimmed = raw.trim()
        val suffixIndex = trimmed.indexOf(SUFFIX_MARKER)
        val baseName: String
        if (suffixIndex >= 0) {
            val suffixBot = trimmed.substring(suffixIndex + SUFFIX_MARKER.length)
            if (botUsername == null || !suffixBot.equals(botUsername, ignoreCase = true)) {
                return Result.WrongBotSuffix(suffixBot)
            }
            baseName = trimmed.substring(0, suffixIndex)
        } else {
            baseName = trimmed
        }
        return if (BASE_NAME_REGEX.matches(baseName)) Result.Valid(baseName) else Result.InvalidFormat
    }
}
