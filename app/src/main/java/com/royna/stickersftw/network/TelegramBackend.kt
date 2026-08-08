package com.royna.stickersftw.network

import com.royna.stickersftw.network.dto.BotInfoDto
import com.royna.stickersftw.network.dto.StickerSetDto
import com.royna.stickersftw.network.dto.UserVerifyDto
import java.io.File

/** Everything [com.royna.stickersftw.data.StickerPackRepository] needs from
 * "however we talk to Telegram right now" -- implemented once against the
 * companion server ([ServerBackend]) and once against Telegram's Bot API
 * directly ([DirectTelegramBackend]). Methods return [ApiResult] for
 * HTTP-level (non-2xx) outcomes but may still throw [java.io.IOException]
 * for real transport failures -- callers keep wrapping calls with
 * [withRateLimitRetry]/[retryTransientErrors] exactly as they did against
 * the raw Retrofit client before this abstraction existed. */
interface TelegramBackend {
    suspend fun getSet(name: String, force: Boolean = false): ApiResult<StickerSetDto>

    /** Streams the sticker's bytes to [output] and returns the sticker's
     * Content-Type (or a stand-in, see [ServerBackend]/[DirectTelegramBackend]),
     * or `null` on failure. [contentTypeHint] is
     * [com.royna.stickersftw.network.dto.StickerDto.knownContentType] --
     * ignored by [ServerBackend] (which trusts the real response header),
     * used verbatim by [DirectTelegramBackend] (which can't trust Telegram's
     * file host to set one). */
    suspend fun downloadSticker(name: String, id: String, output: File, contentTypeHint: String? = null): String?

    suspend fun getBotInfo(): ApiResult<BotInfoDto>

    suspend fun verifyUserStartedChat(userId: String): ApiResult<UserVerifyDto>

    suspend fun pushSticker(
        shortName: String,
        userId: String,
        title: String?,
        format: String,
        emojis: List<String>,
        file: File,
    ): ApiResult<StickerSetDto>

    suspend fun deleteStickerSet(name: String): ApiResult<Unit>

    /** A displayable URL for a sticker's thumbnail. Instant/no network call
     * for [ServerBackend]; resolves a `file_id` via `getFile` for
     * [DirectTelegramBackend], so callers should only invoke this when a
     * thumbnail is actually about to be shown (see the custom sticker
     * picker), not for every sticker in a preview. */
    suspend fun thumbnailUrl(setName: String, id: String, thumbFileId: String?): String?

    /** Single-attempt reachability check -- never throws, mirrors the old
     * `pingServer`'s try/catch-swallow-and-return-false semantics. */
    suspend fun ping(): Boolean
}
