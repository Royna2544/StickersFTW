package com.royna.stickersftw.network

import com.royna.stickersftw.network.telegram.TelegramBotApi
import com.royna.stickersftw.network.telegram.TelegramFileApi
import com.royna.stickersftw.network.telegram.dto.TgChat
import com.royna.stickersftw.network.telegram.dto.TgEnvelope
import com.royna.stickersftw.network.telegram.dto.TgFile
import com.royna.stickersftw.network.telegram.dto.TgPhotoSize
import com.royna.stickersftw.network.telegram.dto.TgSticker
import com.royna.stickersftw.network.telegram.dto.TgStickerSet
import com.royna.stickersftw.network.telegram.dto.TgUser
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun RequestBody.text(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}

private fun <T> errorEnvelope(code: Int, description: String): Response<TgEnvelope<T>> = Response.error(
    code,
    """{"ok":false,"error_code":$code,"description":"$description"}""".toResponseBody("application/json".toMediaType()),
)

/** Fake [TelegramBotApi] -- returns canned envelopes and records the
 * `name`/`stickers` multipart fields so tests can assert on the `_by_<bot>`
 * suffix and create-vs-add branching without a real HTTP client. */
private class FakeTelegramBotApi(
    private val username: String? = "mybot",
    private val stickerSet: TgStickerSet? = null,
    private val chatResult: Response<TgEnvelope<TgChat>>? = null,
) : TelegramBotApi {
    var createCalled = false
    var addCalled = false
    var capturedName: String? = null

    override suspend fun getMe(): Response<TgEnvelope<TgUser>> =
        Response.success(TgEnvelope(ok = true, result = TgUser(id = 1, username = username)))

    override suspend fun getStickerSet(name: String): Response<TgEnvelope<TgStickerSet>> =
        Response.success(TgEnvelope(ok = true, result = stickerSet ?: TgStickerSet(name, "Title", emptyList())))

    override suspend fun getFile(fileId: String): Response<TgEnvelope<TgFile>> =
        Response.success(TgEnvelope(ok = true, result = TgFile(fileId = fileId, filePath = "stickers/$fileId")))

    override suspend fun getChat(chatId: String): Response<TgEnvelope<TgChat>> =
        chatResult ?: Response.success(TgEnvelope(ok = true, result = TgChat(id = 1)))

    override suspend fun createNewStickerSet(
        userId: RequestBody,
        name: RequestBody,
        title: RequestBody,
        stickers: RequestBody,
        stickerFormat: RequestBody,
        sticker: MultipartBody.Part,
    ): Response<TgEnvelope<Boolean>> {
        createCalled = true
        capturedName = name.text()
        return Response.success(TgEnvelope(ok = true, result = true))
    }

    override suspend fun addStickerToSet(
        userId: RequestBody,
        name: RequestBody,
        sticker: RequestBody,
        stickerFile: MultipartBody.Part,
    ): Response<TgEnvelope<Boolean>> {
        addCalled = true
        capturedName = name.text()
        return Response.success(TgEnvelope(ok = true, result = true))
    }

    override suspend fun deleteStickerSet(name: String): Response<TgEnvelope<Boolean>> =
        Response.success(TgEnvelope(ok = true, result = true))
}

private class FakeTelegramFileApi : TelegramFileApi {
    override suspend fun downloadFile(path: String): Response<ResponseBody> =
        Response.success("stub-bytes".toResponseBody("application/octet-stream".toMediaType()))
}

class DirectTelegramBackendTest {

    private fun tempStickerFile(): File = File.createTempFile("sticker", ".webp").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }

    @Test
    fun `getSet maps is_video and is_animated into knownContentType`() = runTest {
        val set = TgStickerSet(
            name = "ducks",
            title = "Ducks",
            stickers = listOf(
                TgSticker(fileId = "vid", width = 512, height = 512, isVideo = true),
                TgSticker(fileId = "anim", width = 512, height = 512, isAnimated = true),
                TgSticker(fileId = "static", width = 512, height = 512, thumbnail = TgPhotoSize("thumb1", 96, 96)),
            ),
        )
        val backend = DirectTelegramBackend("tok", FakeTelegramBotApi(stickerSet = set), FakeTelegramFileApi())

        val result = backend.getSet("ducks") as ApiResult.Success
        val stickers = result.value.stickers.associateBy { it.id }

        assertEquals("video/webm", stickers.getValue("vid").knownContentType)
        assertEquals("application/x-tgsticker", stickers.getValue("anim").knownContentType)
        assertEquals("image/webp", stickers.getValue("static").knownContentType)
        assertEquals("thumb1", stickers.getValue("static").thumb)
    }

    @Test
    fun `downloadSticker returns the content-type hint instead of the real header`() = runTest {
        val backend = DirectTelegramBackend("tok", FakeTelegramBotApi(), FakeTelegramFileApi())
        val output = File.createTempFile("out", ".bin").apply { deleteOnExit() }

        val contentType = backend.downloadSticker("set", "id", output, contentTypeHint = "video/webm")

        assertEquals("video/webm", contentType)
        assertTrue(output.readBytes().isNotEmpty())
    }

    @Test
    fun `pushSticker creates a new set with the _by_bot suffix when title is present`() = runTest {
        val api = FakeTelegramBotApi(username = "mybot")
        val backend = DirectTelegramBackend("tok", api, FakeTelegramFileApi())

        val result = backend.pushSticker(
            shortName = "mypack",
            userId = "123",
            title = "My Pack",
            format = "static",
            emojis = listOf("🙂"),
            file = tempStickerFile(),
        ) as ApiResult.Success

        assertTrue(api.createCalled)
        assertFalse(api.addCalled)
        assertEquals("mypack_by_mybot", api.capturedName)
        assertEquals("mypack_by_mybot", result.value.name)
    }

    @Test
    fun `pushSticker adds to the existing set when title is null`() = runTest {
        val api = FakeTelegramBotApi(username = "mybot")
        val backend = DirectTelegramBackend("tok", api, FakeTelegramFileApi())

        backend.pushSticker(
            shortName = "mypack",
            userId = "123",
            title = null,
            format = "video",
            emojis = listOf("🙂"),
            file = tempStickerFile(),
        )

        assertFalse(api.createCalled)
        assertTrue(api.addCalled)
        assertEquals("mypack_by_mybot", api.capturedName)
    }

    @Test
    fun `verifyUserStartedChat maps a successful getChat to started true`() = runTest {
        val backend = DirectTelegramBackend("tok", FakeTelegramBotApi(), FakeTelegramFileApi())
        val result = backend.verifyUserStartedChat("123") as ApiResult.Success
        assertTrue(result.value.started)
    }

    @Test
    fun `verifyUserStartedChat maps HTTP 400 to started false, not a failure`() = runTest {
        val api = FakeTelegramBotApi(chatResult = errorEnvelope(400, "Bad Request: chat not found"))
        val backend = DirectTelegramBackend("tok", api, FakeTelegramFileApi())

        val result = backend.verifyUserStartedChat("123") as ApiResult.Success

        assertFalse(result.value.started)
    }

    @Test
    fun `verifyUserStartedChat surfaces other HTTP errors as a failure`() = runTest {
        val api = FakeTelegramBotApi(chatResult = errorEnvelope(500, "Internal error"))
        val backend = DirectTelegramBackend("tok", api, FakeTelegramFileApi())

        val result = backend.verifyUserStartedChat("123")

        assertTrue(result is ApiResult.Failure)
    }
}
