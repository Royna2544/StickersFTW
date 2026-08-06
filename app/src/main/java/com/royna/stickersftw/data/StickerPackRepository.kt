package com.royna.stickersftw.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.royna.stickersftw.R
import com.royna.stickersftw.conversion.PackConversionPlanner
import com.royna.stickersftw.conversion.PlannerResult
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.conversion.StickerConversionPipeline
import com.royna.stickersftw.conversion.StickerConvertResult
import com.royna.stickersftw.conversion.StickerMediaType
import com.royna.stickersftw.conversion.StickerTypeClassifier
import com.royna.stickersftw.data.local.AppDatabase
import com.royna.stickersftw.data.local.PackDao
import com.royna.stickersftw.data.local.PackEntity
import com.royna.stickersftw.data.local.PackWithStickers
import com.royna.stickersftw.data.local.StickerDao
import com.royna.stickersftw.data.local.StickerEntity
import com.royna.stickersftw.data.model.PackOperationProgress
import com.royna.stickersftw.data.model.PackPreview
import com.royna.stickersftw.data.model.PreviewResult
import com.royna.stickersftw.data.model.PreviewSticker
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramPushState
import com.royna.stickersftw.network.RetrofitProvider
import com.royna.stickersftw.network.TelegramStickersApi
import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import com.royna.stickersftw.network.toApiErrorOrNull
import com.royna.stickersftw.network.withRateLimitRetry
import com.royna.stickersftw.whatsapp.WhatsAppContract
import com.royna.stickersftw.whatsapp.WhatsAppIntents
import com.royna.stickersftw.whatsapp.WhatsAppWhitelistChecker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Unifies Room persistence, the network client, the conversion pipeline,
 * and WhatsApp registration behind one API the ViewModel drives. Constructed
 * manually (no DI framework), mirroring the existing SettingsRepository. */
class StickerPackRepository(private val appContext: Context) {
    private val database = AppDatabase.getInstance(appContext)
    private val packDao: PackDao = database.packDao()
    private val stickerDao: StickerDao = database.stickerDao()

    fun observePacks(): Flow<List<StickerPack>> =
        packDao.observePacksWithStickers().map { list -> list.map { it.toUiModel() } }

    // ---- Fetch (Telegram -> convert -> WhatsApp) --------------------------

    suspend fun previewTelegramPack(serverUrl: String, input: String): PreviewResult {
        val shortName = extractShortName(input)
        if (shortName.isBlank()) {
            return PreviewResult.Error(appContext.getString(R.string.err_enter_pack_link))
        }

        val api = RetrofitProvider.apiFor(serverUrl)
        val response = try {
            withRateLimitRetry { api.getSet(shortName) }
        } catch (e: Exception) {
            return PreviewResult.Error(describeNetworkError(e))
        }
        response.toApiErrorOrNull()?.let { return PreviewResult.Error(it.userMessage) }
        val dto = response.body() ?: return PreviewResult.Error(appContext.getString(R.string.err_empty_response))

        return when (val countResult = PackConversionPlanner.applyCountRules(dto.stickers)) {
            is PlannerResult.Rejected -> PreviewResult.Error(countResult.reason)
            is PlannerResult.Ok -> {
                val partRanges = PackConversionPlanner.computePartRanges(dto.stickers.size)
                PreviewResult.Loaded(
                    PackPreview(
                        shortName = dto.name,
                        title = dto.title,
                        totalStickerCount = dto.stickers.size,
                        partCount = partRanges.size,
                        stickers = dto.stickers.map { PreviewSticker(it.id, it.emoji) },
                        emojis = dto.stickers.mapNotNull { it.emoji }.distinct().take(8),
                        warning = if (partRanges.size > 1) {
                            val stickersPhrase = appContext.resources.getQuantityString(
                                R.plurals.stickers_count,
                                dto.stickers.size,
                                dto.stickers.size,
                            )
                            val partsPhrase = appContext.resources.getQuantityString(
                                R.plurals.parts_count,
                                partRanges.size,
                                partRanges.size,
                            )
                            appContext.getString(R.string.warn_pack_split, stickersPhrase, partsPhrase)
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    fun importAndConvert(
        packId: String,
        serverUrl: String,
        input: String,
        partIndex: Int = 0,
    ): Flow<PackOperationProgress> = flow {
        val fetched = fetchStickerSet(serverUrl, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val countResult = PackConversionPlanner.applyCountRules(setDto.stickers)
        if (countResult is PlannerResult.Rejected) {
            emit(PackOperationProgress.Failed(countResult.reason))
            return@flow
        }

        val partRanges = PackConversionPlanner.computePartRanges(setDto.stickers.size)
        if (partIndex !in partRanges.indices) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_invalid_part)))
            return@flow
        }
        val stickerDtos = setDto.stickers.slice(partRanges[partIndex])
        val titleSuffix = if (partRanges.size > 1) " (Part ${partIndex + 1}/${partRanges.size})" else ""

        convertAndPersistImportedPack(packId, serverUrl, input, shortNameInput, setDto, stickerDtos, titleSuffix, partIndex)
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_import_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Re-fetches an already-imported pack's Telegram source and re-slices it
     * from scratch using the same part index it was originally imported
     * with (always 0 for a custom or single-part import) -- this is what
     * "Update" means: not a merge/reconciliation, a fresh re-import under
     * the same pack id. */
    fun applyPackUpdate(packId: String, serverUrl: String): Flow<PackOperationProgress> = flow {
        val pack = packDao.getPack(packId) ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_found)))
            return@flow
        }
        val setName = pack.telegramSetName ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_no_linked_telegram_source)))
            return@flow
        }
        val input = pack.sourceUrl ?: setName
        val fetched = fetchStickerSet(serverUrl, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val countResult = PackConversionPlanner.applyCountRules(setDto.stickers)
        if (countResult is PlannerResult.Rejected) {
            emit(PackOperationProgress.Failed(countResult.reason))
            return@flow
        }

        val partRanges = PackConversionPlanner.computePartRanges(setDto.stickers.size)
        val partIndex = pack.importPartIndex.coerceIn(partRanges.indices)
        val stickerDtos = setDto.stickers.slice(partRanges[partIndex])
        val titleSuffix = if (partRanges.size > 1) " (Part ${partIndex + 1}/${partRanges.size})" else ""

        convertAndPersistImportedPack(packId, serverUrl, input, shortNameInput, setDto, stickerDtos, titleSuffix, partIndex)
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_update_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Re-fetches every eligible imported pack's Telegram source and flags
     * [PackEntity.updateAvailable] when it has drifted from the signature
     * captured at the last import/update. Drives My Packs' pull-to-refresh;
     * a single pack's network failure is swallowed so it doesn't block the
     * rest of the sweep. */
    suspend fun checkForUpdates(serverUrl: String) {
        val candidates = packDao.getUpdateCheckCandidates()
        if (candidates.isEmpty()) return
        val api = RetrofitProvider.apiFor(serverUrl)
        for (pack in candidates) {
            val setName = pack.telegramSetName ?: continue
            val response = try {
                withRateLimitRetry { api.getSet(setName) }
            } catch (_: Exception) {
                continue
            }
            if (!response.isSuccessful) continue
            val dto = response.body() ?: continue
            val freshSignature = SourceSignature.compute(dto)
            if (pack.sourceSignature != null && freshSignature != pack.sourceSignature) {
                packDao.setUpdateAvailable(pack.id, true)
            }
        }
    }

    suspend fun setUpdateCheckEnabled(packId: String, enabled: Boolean) {
        packDao.setUpdateCheckEnabled(packId, enabled)
    }

    /** Imports an arbitrary, hand-picked subset of the source pack's
     * stickers (order preserved) instead of a contiguous part -- backs the
     * custom sticker picker. */
    fun importAndConvertCustom(
        packId: String,
        serverUrl: String,
        input: String,
        selectedIds: Set<String>,
    ): Flow<PackOperationProgress> = flow {
        val fetched = fetchStickerSet(serverUrl, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val stickerDtos = setDto.stickers.filter { it.id in selectedIds }
        if (stickerDtos.size < SizeBudget.MIN_STICKERS) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_select_at_least, SizeBudget.MIN_STICKERS)))
            return@flow
        }
        if (stickerDtos.size > SizeBudget.MAX_STICKERS) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_select_at_most, SizeBudget.MAX_STICKERS)))
            return@flow
        }

        convertAndPersistImportedPack(packId, serverUrl, input, shortNameInput, setDto, stickerDtos, " (Custom)")
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_import_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Fetches and validates the sticker set's metadata, emitting a
     * [PackOperationProgress.Failed] and returning null on any failure so
     * callers can just bail out with `?: return@flow`. */
    private suspend fun FlowCollector<PackOperationProgress>.fetchStickerSet(
        serverUrl: String,
        input: String,
    ): Pair<String, StickerSetDto>? {
        val shortNameInput = extractShortName(input)
        if (shortNameInput.isBlank()) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_enter_pack_link)))
            return null
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_reading_metadata), 0.05f))
        val api = RetrofitProvider.apiFor(serverUrl)
        val setResponse = try {
            withRateLimitRetry { api.getSet(shortNameInput) }
        } catch (e: Exception) {
            emit(PackOperationProgress.Failed(describeNetworkError(e)))
            return null
        }
        setResponse.toApiErrorOrNull()?.let {
            emit(PackOperationProgress.Failed(it.userMessage))
            return null
        }
        val setDto = setResponse.body() ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_empty_response)))
            return null
        }
        return shortNameInput to setDto
    }

    /** Shared body for both [importAndConvert] and [importAndConvertCustom]:
     * persists the pack/sticker rows, downloads and converts each selected
     * sticker, builds the tray icon, and finalizes the pack -- the only
     * difference between the two callers is which stickers were selected
     * and how the title is suffixed. */
    private suspend fun FlowCollector<PackOperationProgress>.convertAndPersistImportedPack(
        packId: String,
        serverUrl: String,
        input: String,
        shortNameInput: String,
        setDto: StickerSetDto,
        stickerDtos: List<StickerDto>,
        titleSuffix: String,
        partIndex: Int = 0,
    ) {
        val api = RetrofitProvider.apiFor(serverUrl)
        val now = System.currentTimeMillis()
        // If this packId already exists, we're re-running an update: wipe the
        // previous slice's rows and files before laying down the fresh one,
        // and preserve user state (pin/whatsapp) that has nothing to do with
        // the pack's content.
        val existing = packDao.getPack(packId)
        val packDirForWipe = File(appContext.filesDir, "packs/$packId")
        if (existing != null) {
            stickerDao.deleteForPack(packId)
            File(packDirForWipe, "original").deleteRecursively()
            File(packDirForWipe, "converted").deleteRecursively()
        }
        packDao.upsert(
            PackEntity(
                id = packId,
                origin = PackOrigin.Imported.name,
                telegramSetName = setDto.name,
                pushShortName = null,
                sourceUrl = input,
                title = setDto.title + titleSuffix,
                publisher = "@$shortNameInput",
                stickerCount = stickerDtos.size,
                isAnimatedPack = false,
                status = PackStatus.Downloading.name,
                errorMessage = null,
                warningMessage = null,
                trayIconPath = existing?.trayIconPath,
                isPinned = existing?.isPinned ?: false,
                whatsappAdded = existing?.whatsappAdded ?: false,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
                sourceSignature = SourceSignature.compute(setDto),
                updateAvailable = false,
                updateCheckEnabled = existing?.updateCheckEnabled ?: true,
                importPartIndex = partIndex,
            ),
        )
        stickerDao.upsertAll(
            stickerDtos.mapIndexed { index, dto ->
                StickerEntity(
                    packId = packId,
                    remoteId = dto.id,
                    position = index,
                    emojis = dto.emoji ?: "",
                    sniffedContentType = null,
                    sourceLocalUri = null,
                    isVideo = false,
                    originalFilePath = null,
                    convertedWhatsappPath = null,
                    convertedTelegramPath = null,
                    conversionStatus = "Pending",
                    conversionError = null,
                )
            },
        )

        val packDir = File(appContext.filesDir, "packs/$packId")
        val originalDir = File(packDir, "original")
        val convertedDir = File(packDir, "converted")

        val total = stickerDtos.size
        val sniffedTypes = mutableListOf<StickerMediaType>()
        val downloadedFiles = mutableListOf<Triple<String, File, StickerMediaType>>()

        for ((index, dto) in stickerDtos.withIndex()) {
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_downloading_sticker, index + 1, total),
                    0.05f + 0.40f * (index + 1) / total,
                ),
            )
            val originalFile = File(originalDir, sanitizeFileName(dto.id))
            val contentType = downloadSticker(api, setDto.name, dto.id, originalFile)
            if (contentType == null) {
                updateStickerByRemoteId(packId, dto.id) {
                    it.copy(conversionStatus = "Failed", conversionError = appContext.getString(R.string.err_download_failed))
                }
                continue
            }
            val type = StickerTypeClassifier.classify(contentType).let {
                if (it == StickerMediaType.Unknown) StickerTypeClassifier.reclassifyUnknown(originalFile) else it
            }
            sniffedTypes.add(type)
            downloadedFiles.add(Triple(dto.id, originalFile, type))
            updateStickerByRemoteId(packId, dto.id) {
                it.copy(
                    originalFilePath = originalFile.absolutePath,
                    sniffedContentType = contentType,
                    conversionStatus = "Downloaded",
                )
            }
        }

        if (downloadedFiles.isEmpty()) {
            val message = appContext.getString(R.string.err_no_stickers_downloaded)
            finalizePackFailed(packId, message)
            emit(PackOperationProgress.Failed(message))
            return
        }

        val packIsAnimated = PackConversionPlanner.classifyPackIsAnimated(sniffedTypes)

        var convertedCount = 0
        var firstConvertedFile: File? = null
        var firstConvertedType: StickerMediaType? = null

        for ((index, item) in downloadedFiles.withIndex()) {
            val (remoteId, file, type) = item
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_converting_sticker, index + 1, downloadedFiles.size),
                    0.45f + 0.40f * (index + 1) / downloadedFiles.size,
                ),
            )
            val outputFile = File(convertedDir, "${sanitizeFileName(remoteId)}.webp")
            when (
                val result = StickerConversionPipeline.convertForWhatsapp(
                    appContext,
                    file,
                    outputFile,
                    type,
                    packIsAnimated,
                )
            ) {
                is StickerConvertResult.Success -> {
                    convertedCount++
                    if (firstConvertedFile == null) {
                        firstConvertedFile = file
                        firstConvertedType = type
                    }
                    updateStickerByRemoteId(packId, remoteId) {
                        it.copy(
                            convertedWhatsappPath = outputFile.absolutePath,
                            conversionStatus = if (result.warning != null) "DoneWithWarning" else "Done",
                            conversionError = result.warning,
                        )
                    }
                }
                is StickerConvertResult.Failed -> {
                    updateStickerByRemoteId(packId, remoteId) {
                        it.copy(conversionStatus = "Failed", conversionError = result.reason)
                    }
                }
            }
        }

        if (convertedCount == 0) {
            val message = appContext.getString(R.string.err_no_stickers_converted)
            finalizePackFailed(packId, message)
            emit(PackOperationProgress.Failed(message))
            return
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_building_tray_icon), 0.9f))
        val trayFile = File(packDir, "tray.webp")
        val trayReady = firstConvertedFile != null && firstConvertedType != null &&
            StickerConversionPipeline.buildTrayIcon(firstConvertedFile, firstConvertedType, trayFile) is StickerConvertResult.Success

        finalizePackReady(
            packId,
            packIsAnimated,
            trayFile.absolutePath.takeIf { trayReady },
        )

        emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
        emit(PackOperationProgress.Complete(packId))
    }

    // ---- Create (local media -> Telegram push and/or WhatsApp) ------------

    suspend fun createPack(items: List<PickedMediaItem>, title: String, shortName: String): String {
        val packId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        packDao.upsert(
            PackEntity(
                id = packId,
                origin = PackOrigin.Created.name,
                telegramSetName = null,
                pushShortName = shortName,
                sourceUrl = null,
                title = title,
                publisher = "You",
                stickerCount = items.size,
                isAnimatedPack = false,
                status = PackStatus.Building.name,
                errorMessage = null,
                warningMessage = null,
                trayIconPath = null,
                isPinned = false,
                whatsappAdded = false,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        stickerDao.upsertAll(
            items.mapIndexed { index, item ->
                StickerEntity(
                    packId = packId,
                    remoteId = null,
                    position = index,
                    emojis = item.emoji,
                    sniffedContentType = null,
                    sourceLocalUri = item.uri,
                    isVideo = item.kind == PickedMediaKind.Video,
                    originalFilePath = null,
                    convertedWhatsappPath = null,
                    convertedTelegramPath = null,
                    conversionStatus = "Pending",
                    conversionError = null,
                )
            },
        )
        return packId
    }

    fun publishPack(
        packId: String,
        pushToTelegram: Boolean,
        addToWhatsapp: Boolean,
        serverUrl: String,
        telegramUserId: String,
    ): Flow<PackOperationProgress> = flow {
        val pack = packDao.getPack(packId) ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_found)))
            return@flow
        }
        val stickers = stickerDao.getStickersOnce(packId)
        if (stickers.isEmpty()) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_no_stickers)))
            return@flow
        }

        val packDir = File(appContext.filesDir, "packs/$packId")

        val localFiles = mutableListOf<Pair<StickerEntity, File>>()
        for ((index, sticker) in stickers.withIndex()) {
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_preparing_media, index + 1, stickers.size),
                    0.05f + 0.15f * (index + 1) / stickers.size,
                ),
            )
            val uriString = sticker.sourceLocalUri
            val cacheFile = File(packDir, "original/${sticker.rowId}.bin")
            if (uriString != null && copyUriToFile(uriString, cacheFile)) {
                localFiles.add(sticker to cacheFile)
                updateStickerByRowId(sticker.rowId) { it.copy(originalFilePath = cacheFile.absolutePath) }
            } else {
                updateStickerByRowId(sticker.rowId) {
                    it.copy(conversionStatus = "Failed", conversionError = appContext.getString(R.string.err_could_not_read_sticker_media))
                }
            }
        }

        if (localFiles.isEmpty()) {
            val message = appContext.getString(R.string.err_could_not_read_media)
            finalizePackFailed(packId, message)
            emit(PackOperationProgress.Failed(message))
            return@flow
        }

        val packIsAnimated = PackConversionPlanner.classifyPackIsAnimated(
            localFiles.map { (sticker, _) -> if (sticker.isVideo) StickerMediaType.Video else StickerMediaType.Static },
        )

        var whatsappConvertedCount = 0
        var firstConvertedFile: File? = null
        var firstConvertedType: StickerMediaType? = null

        if (addToWhatsapp) {
            for ((index, entry) in localFiles.withIndex()) {
                val (sticker, file) = entry
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_converting_whatsapp, index + 1, localFiles.size),
                        0.2f + 0.3f * (index + 1) / localFiles.size,
                    ),
                )
                val type = if (sticker.isVideo) StickerMediaType.Video else StickerMediaType.Static
                val output = File(packDir, "converted/${sticker.rowId}.webp")
                when (
                    val result = StickerConversionPipeline.convertForWhatsapp(appContext, file, output, type, packIsAnimated)
                ) {
                    is StickerConvertResult.Success -> {
                        whatsappConvertedCount++
                        if (firstConvertedFile == null) {
                            firstConvertedFile = file
                            firstConvertedType = type
                        }
                        updateStickerByRowId(sticker.rowId) { it.copy(convertedWhatsappPath = output.absolutePath) }
                    }
                    is StickerConvertResult.Failed -> {
                        updateStickerByRowId(sticker.rowId) {
                            it.copy(conversionStatus = "Failed", conversionError = result.reason)
                        }
                    }
                }
            }
        }

        var telegramPushedFullName: String? = pack.telegramSetName
        var telegramPushWarning: String? = null

        if (pushToTelegram) {
            if (telegramUserId.isBlank()) {
                telegramPushWarning = appContext.getString(R.string.err_set_telegram_user_id)
            } else {
                val shortName = pack.pushShortName
                if (shortName == null) {
                    telegramPushWarning = appContext.getString(R.string.err_missing_short_name)
                } else {
                    val api = RetrofitProvider.apiFor(serverUrl)
                    for ((index, entry) in localFiles.withIndex()) {
                        val (sticker, file) = entry
                        emit(
                            PackOperationProgress.Progress(
                                appContext.getString(R.string.stage_pushing_telegram, index + 1, localFiles.size),
                                0.55f + 0.35f * (index + 1) / localFiles.size,
                            ),
                        )
                        val telegramOutput = File(packDir, "telegram/${sticker.rowId}.bin")
                        val format = if (sticker.isVideo) "video" else "static"
                        when (val convertResult = StickerConversionPipeline.convertForTelegram(file, telegramOutput, sticker.isVideo)) {
                            is StickerConvertResult.Failed -> {
                                updateStickerByRowId(sticker.rowId) {
                                    it.copy(conversionStatus = "Failed", conversionError = convertResult.reason)
                                }
                            }
                            is StickerConvertResult.Success -> {
                                val emojiList = sticker.emojis.split(',').filter { it.isNotBlank() }
                                val pushResult = pushOneSticker(
                                    api = api,
                                    shortName = shortName,
                                    userId = telegramUserId,
                                    title = if (telegramPushedFullName == null) pack.title else null,
                                    file = File(convertResult.convertedPath),
                                    format = format,
                                    emojis = emojiList,
                                )
                                when (pushResult) {
                                    is PushOneResult.Success -> {
                                        telegramPushedFullName = pushResult.fullName
                                        updateStickerByRowId(sticker.rowId) {
                                            it.copy(convertedTelegramPath = convertResult.convertedPath)
                                        }
                                    }
                                    is PushOneResult.Failed -> {
                                        telegramPushWarning = pushResult.reason
                                        updateStickerByRowId(sticker.rowId) {
                                            it.copy(conversionStatus = "Failed", conversionError = pushResult.reason)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    telegramPushedFullName?.let {
                        packDao.setTelegramSetName(packId, it, System.currentTimeMillis())
                    }
                }
            }
        }

        when {
            addToWhatsapp && whatsappConvertedCount > 0 -> {
                emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_building_tray_icon), 0.92f))
                val trayFile = File(packDir, "tray.webp")
                val trayReady = firstConvertedFile != null && firstConvertedType != null &&
                    StickerConversionPipeline.buildTrayIcon(firstConvertedFile, firstConvertedType, trayFile) is StickerConvertResult.Success
                finalizePackReady(packId, packIsAnimated, trayFile.absolutePath.takeIf { trayReady }, telegramPushWarning)
            }
            pushToTelegram && telegramPushedFullName != null -> {
                finalizePackReady(packId, packIsAnimated, pack.trayIconPath, telegramPushWarning)
            }
            else -> {
                val reason = telegramPushWarning ?: appContext.getString(R.string.err_nothing_published)
                finalizePackFailed(packId, reason)
                emit(PackOperationProgress.Failed(reason))
                return@flow
            }
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_done), 1f))
        emit(PackOperationProgress.Complete(packId))
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_publish_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    // ---- Shared pack management --------------------------------------------

    suspend fun setPinned(id: String, pinned: Boolean) {
        packDao.setPinned(id, pinned)
    }

    suspend fun deletePack(id: String) {
        packDao.delete(id)
        File(appContext.filesDir, "packs/$id").deleteRecursively()
    }

    suspend fun refreshWhatsappAdded(id: String) {
        val authority = WhatsAppContract.authorityFor(appContext)
        val added = WhatsAppWhitelistChecker.isWhitelisted(appContext, authority, id, business = false)
            ?: WhatsAppWhitelistChecker.isWhitelisted(appContext, authority, id, business = true)
        if (added != null) {
            packDao.setWhatsappAdded(id, added)
        }
    }

    fun buildAddToWhatsappIntent(packId: String, packTitle: String, business: Boolean): Intent =
        WhatsAppIntents.buildAddPackIntent(
            authority = WhatsAppContract.authorityFor(appContext),
            packId = packId,
            packTitle = packTitle,
            targetPackage = if (business) "com.whatsapp.w4b" else "com.whatsapp",
        )

    // ---- Internal helpers ---------------------------------------------------

    private fun PackWithStickers.toUiModel(): StickerPack {
        val sortedStickers = stickers.sortedBy { it.position }
        return StickerPack(
            id = pack.id,
            title = pack.title,
            author = pack.publisher,
            origin = PackOrigin.valueOf(pack.origin),
            stickerCount = pack.stickerCount,
            isAnimated = pack.isAnimatedPack,
            isPinned = pack.isPinned,
            updatedLabel = formatUpdatedLabel(pack.updatedAtMillis),
            sourceUrl = pack.sourceUrl,
            status = PackStatus.valueOf(pack.status),
            errorMessage = pack.errorMessage,
            warningMessage = pack.warningMessage,
            trayIconPath = pack.trayIconPath,
            previewStickerPaths = sortedStickers.mapNotNull { it.convertedWhatsappPath }.take(12),
            previewEmojis = sortedStickers
                .flatMap { it.emojis.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(8),
            whatsappAdded = pack.whatsappAdded,
            telegramPushState = pack.telegramSetName
                ?.let { TelegramPushState.Pushed(it) }
                ?: TelegramPushState.NotPushed,
            updateAvailable = pack.updateAvailable,
        )
    }

    private fun formatUpdatedLabel(epochMillis: Long): String {
        val days = (System.currentTimeMillis() - epochMillis) / (24 * 60 * 60 * 1000)
        return when {
            days <= 0 -> appContext.getString(R.string.date_today)
            days == 1L -> appContext.getString(R.string.date_yesterday)
            days < 7 -> appContext.resources.getQuantityString(R.plurals.date_days_ago, days.toInt(), days.toInt())
            days < 14 -> appContext.resources.getQuantityString(R.plurals.date_weeks_ago, 1, 1)
            else -> {
                val weeks = (days / 7).toInt()
                appContext.resources.getQuantityString(R.plurals.date_weeks_ago, weeks, weeks)
            }
        }
    }

    private fun extractShortName(input: String): String =
        input.trim().trimEnd('/').substringAfterLast('/')

    private fun sanitizeFileName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "sticker" }

    private fun describeNetworkError(e: Exception): String = when (e) {
        is java.io.IOException -> appContext.getString(R.string.err_could_not_reach_server)
        else -> e.message ?: appContext.getString(R.string.err_unexpected)
    }

    private suspend fun downloadSticker(
        api: TelegramStickersApi,
        setName: String,
        stickerId: String,
        output: File,
    ): String? = try {
        val response = withRateLimitRetry { api.getSticker(setName, stickerId) }
        if (!response.isSuccessful) {
            null
        } else {
            val body = response.body()
            if (body == null) {
                null
            } else {
                output.parentFile?.mkdirs()
                body.byteStream().use { input -> output.outputStream().use { out -> input.copyTo(out) } }
                response.headers()["Content-Type"]
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun copyUriToFile(uriString: String, output: File): Boolean = try {
        output.parentFile?.mkdirs()
        val opened = appContext.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            output.outputStream().use { out -> input.copyTo(out) }
        }
        opened != null
    } catch (_: Exception) {
        false
    }

    private suspend fun updatePack(packId: String, transform: (PackEntity) -> PackEntity) {
        val current = packDao.getPack(packId) ?: return
        packDao.upsert(transform(current).copy(updatedAtMillis = System.currentTimeMillis()))
    }

    private suspend fun finalizePackFailed(packId: String, message: String) {
        updatePack(packId) { it.copy(status = PackStatus.Failed.name, errorMessage = message) }
    }

    private suspend fun finalizePackReady(
        packId: String,
        isAnimated: Boolean,
        trayIconPath: String?,
        warning: String? = null,
    ) {
        updatePack(packId) {
            it.copy(
                status = PackStatus.Ready.name,
                isAnimatedPack = isAnimated,
                trayIconPath = trayIconPath ?: it.trayIconPath,
                warningMessage = warning,
                errorMessage = null,
            )
        }
    }

    private suspend fun updateStickerByRemoteId(
        packId: String,
        remoteId: String,
        transform: (StickerEntity) -> StickerEntity,
    ) {
        val current = stickerDao.findByRemoteId(packId, remoteId) ?: return
        stickerDao.upsert(transform(current))
    }

    private suspend fun updateStickerByRowId(rowId: Long, transform: (StickerEntity) -> StickerEntity) {
        val current = stickerDao.findByRowId(rowId) ?: return
        stickerDao.upsert(transform(current))
    }

    private sealed class PushOneResult {
        data class Success(val fullName: String) : PushOneResult()
        data class Failed(val reason: String) : PushOneResult()
    }

    private suspend fun pushOneSticker(
        api: TelegramStickersApi,
        shortName: String,
        userId: String,
        title: String?,
        file: File,
        format: String,
        emojis: List<String>,
    ): PushOneResult = try {
        val mediaType = if (format == "video") "video/webm".toMediaType() else "image/webp".toMediaType()
        val stickerPart = MultipartBody.Part.createFormData("sticker", file.name, file.asRequestBody(mediaType))
        val plainText = "text/plain".toMediaType()
        val response = withRateLimitRetry {
            api.pushSticker(
                shortName = shortName,
                userId = userId.toRequestBody(plainText),
                title = title?.toRequestBody(plainText),
                format = format.toRequestBody(plainText),
                emojis = emojis.joinToString(",").toRequestBody(plainText),
                sticker = stickerPart,
            )
        }
        val error = response.toApiErrorOrNull()
        if (error != null) {
            PushOneResult.Failed(error.userMessage)
        } else {
            val body = response.body()
            if (body != null) {
                PushOneResult.Success(body.name)
            } else {
                PushOneResult.Failed(appContext.getString(R.string.err_empty_response))
            }
        }
    } catch (e: Exception) {
        PushOneResult.Failed(describeNetworkError(e))
    }
}
