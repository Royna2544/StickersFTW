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
import com.royna.stickersftw.data.model.EmojiChange
import com.royna.stickersftw.data.model.PackPreview
import com.royna.stickersftw.data.model.PackUpdateDiff
import com.royna.stickersftw.data.model.PackUpdateDiffResult
import com.royna.stickersftw.data.model.StickerEntry
import com.royna.stickersftw.data.model.PreviewResult
import com.royna.stickersftw.data.model.PreviewSticker
import com.royna.stickersftw.model.ConversionBias
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.model.StickerGridItem
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.TelegramPushState
import com.royna.stickersftw.network.ApiResult
import com.royna.stickersftw.network.TelegramBackend
import com.royna.stickersftw.network.TelegramBackendConfig
import com.royna.stickersftw.network.TelegramBackendProvider
import com.royna.stickersftw.network.dto.StickerDto
import com.royna.stickersftw.network.dto.StickerSetDto
import com.royna.stickersftw.network.retryTransientErrors
import com.royna.stickersftw.whatsapp.WhatsAppContract
import com.royna.stickersftw.whatsapp.WhatsAppIntents
import com.royna.stickersftw.whatsapp.WhatsAppWhitelistChecker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** How many sticker paths [StickerPack.previewStickerPaths] carries -- three
 * full rows of the pack detail screen's six-column preview grid. Derived on
 * every read rather than stored, so changing it takes effect for packs that
 * are already imported. The full set lives behind "View all stickers". */
private const val PREVIEW_STICKER_LIMIT = 18

/** Unifies Room persistence, the network client, the conversion pipeline,
 * and WhatsApp registration behind one API the ViewModel drives. Constructed
 * manually (no DI framework), mirroring the existing SettingsRepository. */
class StickerPackRepository(private val appContext: Context) {
    private val database = AppDatabase.getInstance(appContext)
    private val packDao: PackDao = database.packDao()
    private val stickerDao: StickerDao = database.stickerDao()

    /** A lightweight reachability check (hits the same cheap /v1/bot route
     * used to show the bot's username) -- deliberately a single attempt with
     * no retry, since this backs interactive "is this server reachable"
     * checks (Settings save, Convert page status) that need a prompt answer,
     * not a resilient background operation. */
    suspend fun pingServer(backendConfig: TelegramBackendConfig): Boolean =
        TelegramBackendProvider.resolve(backendConfig).ping()

    fun observePacks(): Flow<List<StickerPack>> =
        packDao.observePacksWithStickers().map { list -> list.map { it.toUiModel() } }

    /** Every converted sticker in a pack, in order, for the read-only grid
     * viewer -- unlike [StickerPack.previewStickerPaths], not truncated. */
    fun observePackStickers(packId: String): Flow<List<StickerGridItem>> =
        packDao.observePackWithStickers(packId).map { packWithStickers ->
            packWithStickers?.stickers
                ?.sortedBy { it.position }
                ?.mapNotNull { sticker ->
                    sticker.convertedWhatsappPath?.let { StickerGridItem(it, sticker.emojis) }
                }
                .orEmpty()
        }

    // ---- Fetch (Telegram -> convert -> WhatsApp) --------------------------

    suspend fun previewTelegramPack(backendConfig: TelegramBackendConfig, input: String): PreviewResult {
        val shortName = extractShortName(input)
        if (shortName.isBlank()) {
            return PreviewResult.Error(appContext.getString(R.string.err_enter_pack_link))
        }

        val backend = TelegramBackendProvider.resolve(backendConfig)
        val result = try {
            backend.getSet(shortName)
        } catch (e: Exception) {
            return PreviewResult.Error(describeNetworkError(e))
        }
        val dto = when (result) {
            is ApiResult.Failure -> return PreviewResult.Error(result.error.userMessage)
            is ApiResult.Success -> result.value
        }

        return when (val countResult = PackConversionPlanner.applyCountRules(dto.stickers)) {
            is PlannerResult.Rejected -> PreviewResult.Error(countResult.reason)
            is PlannerResult.Ok -> {
                val partRanges = PackConversionPlanner.computePartRanges(dto.stickers.size)
                PreviewResult.Loaded(
                    PackPreview(
                        shortName = dto.name,
                        title = sanitizeTitle(dto.title),
                        totalStickerCount = dto.stickers.size,
                        partCount = partRanges.size,
                        stickers = dto.stickers.map { PreviewSticker(it.id, it.emoji, it.thumb) },
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
        backendConfig: TelegramBackendConfig,
        input: String,
        partIndex: Int = 0,
        bias: ConversionBias = ConversionBias.Auto,
        onMixedPack: suspend (animated: Int, static: Int) -> Boolean = { _, _ -> false },
    ): Flow<PackOperationProgress> = flow {
        placeholderPack(packId, input, partIndex)
        val fetched = fetchStickerSet(packId, backendConfig, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val countResult = PackConversionPlanner.applyCountRules(setDto.stickers)
        if (countResult is PlannerResult.Rejected) {
            failImport(packId, countResult.reason)
            return@flow
        }

        val partRanges = PackConversionPlanner.computePartRanges(setDto.stickers.size)
        if (partIndex !in partRanges.indices) {
            failImport(packId, appContext.getString(R.string.err_invalid_part))
            return@flow
        }
        val stickerDtos = setDto.stickers.slice(partRanges[partIndex])
        val titleSuffix = if (partRanges.size > 1) " (Part ${partIndex + 1}/${partRanges.size})" else ""

        convertAndPersistImportedPack(packId, backendConfig, input, shortNameInput, setDto, stickerDtos, titleSuffix, partIndex, bias, onMixedPack)
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
    fun applyPackUpdate(
        packId: String,
        backendConfig: TelegramBackendConfig,
        bias: ConversionBias = ConversionBias.Auto,
        onMixedPack: suspend (animated: Int, static: Int) -> Boolean = { _, _ -> false },
    ): Flow<PackOperationProgress> = flow {
        val pack = packDao.getPack(packId) ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_found)))
            return@flow
        }
        val setName = pack.telegramSetName ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_no_linked_telegram_source)))
            return@flow
        }
        val input = pack.sourceUrl ?: setName
        val fetched = fetchStickerSet(packId, backendConfig, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val countResult = PackConversionPlanner.applyCountRules(setDto.stickers)
        if (countResult is PlannerResult.Rejected) {
            failImport(packId, countResult.reason)
            return@flow
        }

        val partRanges = PackConversionPlanner.computePartRanges(setDto.stickers.size)
        val partIndex = pack.importPartIndex.coerceIn(partRanges.indices)
        val stickerDtos = setDto.stickers.slice(partRanges[partIndex])
        val titleSuffix = if (partRanges.size > 1) " (Part ${partIndex + 1}/${partRanges.size})" else ""

        convertAndPersistImportedPack(packId, backendConfig, input, shortNameInput, setDto, stickerDtos, titleSuffix, partIndex, bias, onMixedPack)
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
    suspend fun checkForUpdates(backendConfig: TelegramBackendConfig) {
        val candidates = packDao.getUpdateCheckCandidates()
        if (candidates.isEmpty()) return
        val backend = TelegramBackendProvider.resolve(backendConfig)
        for (pack in candidates) {
            val setName = pack.telegramSetName ?: continue
            val result = try {
                backend.getSet(setName)
            } catch (_: Exception) {
                continue
            }
            val dto = (result as? ApiResult.Success)?.value ?: continue
            val freshSignature = SourceSignature.compute(dto)
            if (pack.sourceSignature != null && freshSignature != pack.sourceSignature) {
                packDao.setUpdateAvailable(pack.id, true)
            }
        }
    }

    suspend fun setUpdateCheckEnabled(packId: String, enabled: Boolean) {
        packDao.setUpdateCheckEnabled(packId, enabled)
    }

    sealed class ForceRefreshResult {
        data object UpToDate : ForceRefreshResult()
        data object UpdateAvailable : ForceRefreshResult()
        data class Failed(val reason: String) : ForceRefreshResult()
    }

    /** User-triggered "refresh this pack now" -- unlike [checkForUpdates]'s
     * cache-friendly sweep over every eligible pack, this always tells the
     * server to bypass its cache and re-fetch this one pack from Telegram. */
    suspend fun forceRefreshPack(packId: String, backendConfig: TelegramBackendConfig): ForceRefreshResult {
        val pack = packDao.getPack(packId)
            ?: return ForceRefreshResult.Failed(appContext.getString(R.string.err_pack_not_found))
        val setName = pack.telegramSetName
            ?: return ForceRefreshResult.Failed(appContext.getString(R.string.err_no_linked_telegram_source))
        val backend = TelegramBackendProvider.resolve(backendConfig)
        val result = try {
            retryTransientErrors { backend.getSet(setName, force = true) }
        } catch (e: Exception) {
            return ForceRefreshResult.Failed(describeNetworkError(e))
        }
        val dto = when (result) {
            is ApiResult.Failure -> return ForceRefreshResult.Failed(result.error.userMessage)
            is ApiResult.Success -> result.value
        }
        val freshSignature = SourceSignature.compute(dto)
        return if (pack.sourceSignature != null && freshSignature != pack.sourceSignature) {
            packDao.setUpdateAvailable(packId, true)
            ForceRefreshResult.UpdateAvailable
        } else {
            ForceRefreshResult.UpToDate
        }
    }

    /** Imports an arbitrary, hand-picked subset of the source pack's
     * stickers (order preserved) instead of a contiguous part -- backs the
     * custom sticker picker. */
    fun importAndConvertCustom(
        packId: String,
        backendConfig: TelegramBackendConfig,
        input: String,
        selectedIds: Set<String>,
        bias: ConversionBias = ConversionBias.Auto,
        onMixedPack: suspend (animated: Int, static: Int) -> Boolean = { _, _ -> false },
    ): Flow<PackOperationProgress> = flow {
        placeholderPack(packId, input, CUSTOM_PART_INDEX)
        val fetched = fetchStickerSet(packId, backendConfig, input) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val stickerDtos = setDto.stickers.filter { it.id in selectedIds }
        if (stickerDtos.size < SizeBudget.MIN_STICKERS) {
            failImport(packId, appContext.getString(R.string.err_select_at_least, SizeBudget.MIN_STICKERS))
            return@flow
        }
        if (stickerDtos.size > SizeBudget.MAX_STICKERS) {
            failImport(packId, appContext.getString(R.string.err_select_at_most, SizeBudget.MAX_STICKERS))
            return@flow
        }

        convertAndPersistImportedPack(packId, backendConfig, input, shortNameInput, setDto, stickerDtos, " (Custom)", CUSTOM_PART_INDEX, bias, onMixedPack)
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_import_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Resolves each sticker's displayable thumbnail URL -- instant for the
     * server backend, a `getFile` round trip per sticker for the bot-token
     * backend. Only call this when a thumbnail grid is actually about to be
     * shown (the custom sticker picker), not on every preview load; small
     * bounded concurrency keeps a large set from serializing one request at
     * a time. */
    suspend fun resolveThumbnailUrls(
        backendConfig: TelegramBackendConfig,
        setName: String,
        stickers: List<PreviewSticker>,
    ): Map<String, String> {
        val backend = TelegramBackendProvider.resolve(backendConfig)
        return coroutineScope {
            stickers.chunked(8).flatMap { chunk ->
                chunk.map { sticker ->
                    async {
                        val url = try {
                            backend.thumbnailUrl(setName, sticker.id, sticker.thumb)
                        } catch (_: Exception) {
                            null
                        }
                        sticker.id to url
                    }
                }.awaitAll()
            }
        }.mapNotNull { (id, url) -> url?.let { id to it } }.toMap()
    }

    /** Fetches and validates the sticker set's metadata, emitting a
     * [PackOperationProgress.Failed] and returning null on any failure so
     * callers can just bail out with `?: return@flow`. */
    /** Records the failure on the pack row as well as reporting it to the UI.
     * The two used to drift apart: every early return in an import emitted
     * [PackOperationProgress.Failed] and left the row alone, which was
     * invisible while the row didn't exist yet and stale once it did. */
    private suspend fun FlowCollector<PackOperationProgress>.failImport(
        packId: String,
        message: String,
    ) {
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }

    /** Lays down a minimal row before the first network call.
     *
     * An import used to exist only as in-memory progress state until the set
     * metadata came back, so anything that interrupted that window -- a
     * dropped connection, a rejected fetch, the process going away -- left
     * nothing at all behind: no failed pack to retry, nothing to delete, no
     * evidence the import was ever attempted. Everything here is what's known
     * without asking Telegram; [convertAndPersistImportedPack] overwrites it
     * with the real metadata as soon as there is any.
     *
     * Status is Building, which keeps it out of the update-check candidates
     * and out of the WhatsApp content provider, both of which select on
     * Ready. */
    private suspend fun placeholderPack(packId: String, input: String, partIndex: Int) {
        if (packDao.getPack(packId) != null) return
        val shortName = extractShortName(input)
        val now = System.currentTimeMillis()
        packDao.upsert(
            PackEntity(
                id = packId,
                origin = PackOrigin.Imported.name,
                telegramSetName = shortName.takeIf { it.isNotBlank() },
                pushShortName = null,
                sourceUrl = input,
                title = shortName.ifBlank { appContext.getString(R.string.pack_placeholder_title) },
                publisher = if (shortName.isBlank()) "" else "@$shortName",
                stickerCount = 0,
                isAnimatedPack = false,
                status = PackStatus.Building.name,
                errorMessage = null,
                warningMessage = null,
                trayIconPath = null,
                isPinned = false,
                whatsappAdded = false,
                createdAtMillis = now,
                updatedAtMillis = now,
                sourceSignature = null,
                updateAvailable = false,
                updateCheckEnabled = true,
                importPartIndex = partIndex,
            ),
        )
    }

    private suspend fun FlowCollector<PackOperationProgress>.fetchStickerSet(
        packId: String,
        backendConfig: TelegramBackendConfig,
        input: String,
    ): Pair<String, StickerSetDto>? {
        val shortNameInput = extractShortName(input)
        if (shortNameInput.isBlank()) {
            failImport(packId, appContext.getString(R.string.err_enter_pack_link))
            return null
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_reading_metadata), 0.05f))
        val backend = TelegramBackendProvider.resolve(backendConfig)
        val result = try {
            retryTransientErrors(
                onRetry = { attempt, max ->
                    emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_retrying, attempt, max), 0.05f))
                },
            ) { backend.getSet(shortNameInput) }
        } catch (e: Exception) {
            failImport(packId, describeNetworkError(e))
            return null
        }
        val setDto = when (result) {
            is ApiResult.Failure -> {
                failImport(packId, result.error.userMessage)
                return null
            }
            is ApiResult.Success -> result.value
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
        backendConfig: TelegramBackendConfig,
        input: String,
        shortNameInput: String,
        setDto: StickerSetDto,
        stickerDtos: List<StickerDto>,
        titleSuffix: String,
        partIndex: Int = 0,
        bias: ConversionBias = ConversionBias.Auto,
        onMixedPack: suspend (animated: Int, static: Int) -> Boolean = { _, _ -> false },
    ) {
        val backend = TelegramBackendProvider.resolve(backendConfig)
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
                title = sanitizeTitle(setDto.title) + titleSuffix,
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
        val downloadedFiles = mutableListOf<Triple<String, File, StickerMediaType>>()

        for ((index, dto) in stickerDtos.withIndex()) {
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_downloading_sticker, index + 1, total),
                    0.05f + 0.40f * (index + 1) / total,
                ),
            )
            val originalFile = File(originalDir, sanitizeFileName(dto.id))
            val contentType = downloadSticker(backend, setDto.name, dto.id, originalFile, dto.knownContentType) { attempt, max ->
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_retrying, attempt, max),
                        0.05f + 0.40f * (index + 1) / total,
                    ),
                )
            }
            if (contentType == null) {
                updateStickerByRemoteId(packId, dto.id) {
                    it.copy(conversionStatus = "Failed", conversionError = appContext.getString(R.string.err_download_failed))
                }
                continue
            }
            val type = StickerTypeClassifier.classify(contentType).let {
                if (it == StickerMediaType.Unknown) StickerTypeClassifier.reclassifyUnknown(originalFile) else it
            }
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

        var convertedCount = 0
        var animatedCount = 0
        var staticCount = 0
        var firstConvertedFile: File? = null
        var firstConvertedType: StickerMediaType? = null
        val convertedForFixup = mutableListOf<WhatsappConvertedSticker>()

        // Known only now that every sticker has been downloaded and
        // classified -- the server backend gives no usable type up front.
        // Downloads are the quick part, so this still lands well before the
        // conversion the user would otherwise sit through wondering.
        //
        // Lottie counts as slow too, not just video. Hot Cherry's 17 Lottie
        // stickers took 4:55 with no warning shown at all, because every frame
        // still has to be rendered one at a time and then run through the same
        // re-encode ladder to fit 500KB. Which format it is barely changes how
        // long the user waits.
        val slowFormat = downloadedFiles.any { (_, _, type) ->
            type == StickerMediaType.Video || type == StickerMediaType.AnimatedLottie
        }

        for ((index, item) in downloadedFiles.withIndex()) {
            val (remoteId, file, type) = item
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_converting_sticker, index + 1, downloadedFiles.size),
                    0.45f + 0.40f * (index + 1) / downloadedFiles.size,
                    slowFormat = slowFormat,
                ),
            )
            val outputFile = File(convertedDir, "${sanitizeFileName(remoteId)}.webp")
            when (
                val result = StickerConversionPipeline.convertForWhatsapp(appContext, file, outputFile, type, bias)
            ) {
                is StickerConvertResult.Success -> {
                    convertedCount++
                    if (result.isAnimated) animatedCount++ else staticCount++
                    convertedForFixup.add(WhatsappConvertedSticker(file, type, outputFile, result.isAnimated))
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
        // Derived from what conversion actually produced, not from the
        // source's nominal format -- see StickerConversionPipeline.
        //
        // WhatsApp accepts a pack that is entirely animated or entirely
        // static, never a mix, so one side has to give. Which side is the
        // user's call, and it used to be made silently by majority: a pack of
        // 26 stills and 2 animations quietly flattened both animations to
        // their first frame with nothing said.
        // A sticker the decoder refused leaves the pack smaller than the count
        // recorded when it was queued for download. Left unreconciled the pack
        // claims stickers it does not have, and the user is told nothing at
        // all -- which is how a 4-sticker pack shipped 3 without a word.
        val warnings = mutableListOf<String>()
        val droppedCount = downloadedFiles.size - convertedCount
        if (droppedCount > 0) {
            warnings += appContext.resources.getQuantityString(
                R.plurals.warn_stickers_dropped,
                droppedCount,
                droppedCount,
            )
        }

        var splitPackId: String? = null
        var packIsAnimated = animatedCount >= staticCount && animatedCount > 0
        if (animatedCount > 0 && staticCount > 0) {
            // Both halves have to clear WhatsApp's 3-sticker floor for a split
            // to be worth offering. One or two animated stickers among 27
            // stills would otherwise become a second pack WhatsApp refuses to
            // add -- strictly worse than flattening, and not a trade-off worth
            // interrupting the conversion to ask about.
            val canSplit = animatedCount >= SizeBudget.MIN_STICKERS &&
                staticCount >= SizeBudget.MIN_STICKERS
            if (canSplit && onMixedPack(animatedCount, staticCount)) {
                splitPackId = splitAnimatedIntoOwnPack(packId, setDto, convertedForFixup, bias)
                // Whatever is left in this pack is now one kind throughout.
                packIsAnimated = false
                convertedCount -= animatedCount
                convertedForFixup.removeAll { it.isAnimated }
            } else {
                val minority = if (packIsAnimated) staticCount else animatedCount
                warnings += appContext.resources.getQuantityString(
                    R.plurals.warn_mixed_pack_flattened,
                    minority,
                    minority,
                )
            }
        }
        reconcileWhatsappAnimatedMismatches(convertedForFixup, packIsAnimated, bias)

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
            warning = warnings.joinToString(" ").ifEmpty { null },
            bias = bias,
            stickerCount = convertedCount,
        )

        emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
        emit(PackOperationProgress.Complete(packId, splitPackId))
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
                // Blank means the pack was created for WhatsApp only, so it
                // has no Telegram identity at all. Stored as null rather than
                // "" so a later push reports the missing name properly instead
                // of asking Telegram to register an empty one.
                pushShortName = shortName.ifBlank { null },
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
        backendConfig: TelegramBackendConfig,
        telegramUserId: String,
        bias: ConversionBias = ConversionBias.Auto,
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

        var whatsappConvertedCount = 0
        var animatedCount = 0
        var staticCount = 0
        var firstConvertedFile: File? = null
        var firstConvertedType: StickerMediaType? = null
        val convertedForFixup = mutableListOf<WhatsappConvertedSticker>()

        val slowFormat = localFiles.any { (sticker, _) -> sticker.isVideo }

        if (addToWhatsapp) {
            for ((index, entry) in localFiles.withIndex()) {
                val (sticker, file) = entry
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_converting_whatsapp, index + 1, localFiles.size),
                        0.2f + 0.3f * (index + 1) / localFiles.size,
                        slowFormat = slowFormat,
                    ),
                )
                val type = if (sticker.isVideo) StickerMediaType.Video else StickerMediaType.Static
                val output = File(packDir, "converted/${sticker.rowId}.webp")
                when (
                    val result = StickerConversionPipeline.convertForWhatsapp(appContext, file, output, type, bias)
                ) {
                    is StickerConvertResult.Success -> {
                        whatsappConvertedCount++
                        if (result.isAnimated) animatedCount++ else staticCount++
                        convertedForFixup.add(WhatsappConvertedSticker(file, type, output, result.isAnimated))
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
        // Derived from what conversion actually produced when it ran (see
        // StickerConversionPipeline); falls back to the picked media's own
        // kind when this is a Telegram-only push with no WhatsApp step to
        // observe an outcome from.
        val packIsAnimated = if (whatsappConvertedCount > 0) {
            animatedCount >= staticCount
        } else {
            PackConversionPlanner.classifyPackIsAnimated(
                localFiles.map { (sticker, _) -> if (sticker.isVideo) StickerMediaType.Video else StickerMediaType.Static },
            )
        }
        reconcileWhatsappAnimatedMismatches(convertedForFixup, packIsAnimated, bias)

        var telegramPushedFullName: String? = pack.telegramSetName
        var telegramPushWarning: String? = null
        var telegramPushedCount = 0
        var telegramFailedCount = 0

        if (pushToTelegram) {
            if (telegramUserId.isBlank()) {
                telegramPushWarning = appContext.getString(R.string.err_set_telegram_user_id)
            } else {
                val shortName = pack.pushShortName
                if (shortName == null) {
                    telegramPushWarning = appContext.getString(R.string.err_missing_short_name)
                } else if (checkUserStartedChat(telegramUserId, backendConfig) == false) {
                    telegramPushWarning = appContext.getString(R.string.err_user_not_started_bot)
                } else {
                    val backend = TelegramBackendProvider.resolve(backendConfig)
                    for ((index, entry) in localFiles.withIndex()) {
                        val (sticker, file) = entry
                        emit(
                            PackOperationProgress.Progress(
                                appContext.getString(R.string.stage_pushing_telegram, index + 1, localFiles.size),
                                0.55f + 0.35f * (index + 1) / localFiles.size,
                            ),
                        )
                        if (sticker.convertedTelegramPath != null) {
                            // Already made it to Telegram on a prior push attempt -- retrying
                            // must not re-upload it (that would create a duplicate sticker
                            // in the Telegram set).
                            telegramPushedCount++
                            continue
                        }
                        val telegramOutput = File(packDir, "telegram/${sticker.rowId}.bin")
                        val format = if (sticker.isVideo) "video" else "static"
                        when (val convertResult = StickerConversionPipeline.convertForTelegram(file, telegramOutput, sticker.isVideo)) {
                            is StickerConvertResult.Failed -> {
                                telegramFailedCount++
                                telegramPushWarning = convertResult.reason
                                updateStickerByRowId(sticker.rowId) {
                                    it.copy(conversionStatus = "Failed", conversionError = convertResult.reason)
                                }
                            }
                            is StickerConvertResult.Success -> {
                                val emojiList = sticker.emojis.split(',').filter { it.isNotBlank() }
                                val pushResult = pushOneSticker(
                                    backend = backend,
                                    shortName = shortName,
                                    userId = telegramUserId,
                                    title = if (telegramPushedFullName == null) pack.title else null,
                                    file = File(convertResult.convertedPath),
                                    format = format,
                                    emojis = emojiList,
                                    onRetry = { attempt, max ->
                                        emit(
                                            PackOperationProgress.Progress(
                                                appContext.getString(R.string.stage_retrying, attempt, max),
                                                0.55f + 0.35f * (index + 1) / localFiles.size,
                                            ),
                                        )
                                    },
                                )
                                when (pushResult) {
                                    is PushOneResult.Success -> {
                                        telegramPushedFullName = pushResult.fullName
                                        telegramPushedCount++
                                        updateStickerByRowId(sticker.rowId) {
                                            it.copy(convertedTelegramPath = convertResult.convertedPath)
                                        }
                                    }
                                    is PushOneResult.Failed -> {
                                        telegramFailedCount++
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
                    if (telegramFailedCount > 0 && telegramPushedCount > 0) {
                        telegramPushWarning = appContext.getString(
                            R.string.warn_telegram_partial_push,
                            telegramPushedCount,
                            localFiles.size,
                            telegramPushWarning.orEmpty(),
                        )
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
                finalizePackReady(packId, packIsAnimated, trayFile.absolutePath.takeIf { trayReady }, telegramPushWarning, bias)
            }
            pushToTelegram && telegramPushedFullName != null -> {
                finalizePackReady(packId, packIsAnimated, pack.trayIconPath, telegramPushWarning, bias)
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

    /** Builds the change list shown before an update is accepted.
     *
     * Compares the signature captured at import against a fresh fetch of the
     * same set. Read-only: nothing is written, so backing out of the preview
     * leaves the pack exactly as it was. */
    suspend fun computeUpdateDiff(packId: String, backendConfig: TelegramBackendConfig): PackUpdateDiffResult {
        val pack = packDao.getPack(packId)
            ?: return PackUpdateDiffResult.Error(appContext.getString(R.string.err_pack_not_found))
        val setName = pack.telegramSetName
            ?: return PackUpdateDiffResult.Error(appContext.getString(R.string.err_no_linked_telegram_source))

        val result = try {
            TelegramBackendProvider.resolve(backendConfig).getSet(setName)
        } catch (e: Exception) {
            return PackUpdateDiffResult.Error(describeNetworkError(e))
        }
        val dto = when (result) {
            is ApiResult.Failure -> return PackUpdateDiffResult.Error(result.error.userMessage)
            is ApiResult.Success -> result.value
        }

        if (SourceSignature.compute(dto) == pack.sourceSignature) return PackUpdateDiffResult.UpToDate
        val before = SourceSignature.parse(pack.sourceSignature) ?: return PackUpdateDiffResult.NoBaseline

        val beforeById = before.stickers.associateBy { it.id }
        val afterById = dto.stickers.associate { it.id to it.emoji.orEmpty() }

        return PackUpdateDiffResult.Loaded(
            PackUpdateDiff(
                titleBefore = before.title,
                titleAfter = dto.title,
                added = dto.stickers
                    .filter { it.id !in beforeById }
                    .map { StickerEntry(it.id, it.emoji.orEmpty()) },
                removed = before.stickers
                    .filter { it.id !in afterById }
                    .map { StickerEntry(it.id, it.emoji) },
                emojiChanged = before.stickers.mapNotNull { entry ->
                    val after = afterById[entry.id] ?: return@mapNotNull null
                    if (after == entry.emoji) null else EmojiChange(entry.id, entry.emoji, after)
                },
                countBefore = before.stickers.size,
                countAfter = dto.stickers.size,
            ),
        )
    }

    /** Moves the animated stickers of a mixed pack into a pack of their own,
     * leaving the static ones where they are, so neither kind has to be
     * destroyed to satisfy WhatsApp's all-or-nothing rule.
     *
     * The new pack takes [ANIMATED_SPLIT_PART_INDEX] rather than the part
     * index it came from. Duplicate detection keys on set name plus part
     * index, and two packs claiming the same pair would make re-importing
     * either one ambiguous -- the same reason a hand-picked import uses
     * [CUSTOM_PART_INDEX]. Files move rather than being reconverted: they are
     * already correct, and a rename inside the same directory tree is free
     * next to decoding a video sticker again. */
    private suspend fun splitAnimatedIntoOwnPack(
        packId: String,
        setDto: StickerSetDto,
        converted: List<WhatsappConvertedSticker>,
        bias: ConversionBias,
    ): String? {
        val source = packDao.getPack(packId) ?: return null
        val animated = converted.filter { it.isAnimated }
        if (animated.isEmpty()) return null

        val newPackId = UUID.randomUUID().toString()
        val newDir = File(appContext.filesDir, "packs/$newPackId")
        File(newDir, "converted").mkdirs()
        File(newDir, "original").mkdirs()

        val movedPaths = mutableMapOf<String, String>()
        for (sticker in animated) {
            val target = File(newDir, "converted/${sticker.output.name}")
            if (sticker.output.renameTo(target)) movedPaths[sticker.output.absolutePath] = target.absolutePath
        }

        val now = System.currentTimeMillis()
        val rows = stickerDao.getStickersOnce(packId)
            .filter { it.convertedWhatsappPath in movedPaths }
        packDao.upsert(
            source.copy(
                id = newPackId,
                title = source.title + appContext.getString(R.string.pack_animated_suffix),
                stickerCount = rows.size,
                isAnimatedPack = true,
                status = PackStatus.Ready.name,
                trayIconPath = null,
                whatsappAdded = false,
                createdAtMillis = now,
                updatedAtMillis = now,
                importPartIndex = ANIMATED_SPLIT_PART_INDEX,
                conversionBias = bias.name,
            ),
        )
        stickerDao.upsertAll(
            rows.mapIndexed { index, row ->
                row.copy(
                    rowId = 0,
                    packId = newPackId,
                    position = index,
                    convertedWhatsappPath = movedPaths[row.convertedWhatsappPath],
                )
            },
        )
        rows.forEach { stickerDao.deleteByRowId(it.rowId) }

        val first = rows.firstOrNull()?.convertedWhatsappPath?.let { movedPaths[it] }?.let(::File)
        val trayFile = File(newDir, "tray.webp")
        val trayReady = first != null &&
            StickerConversionPipeline.buildTrayIcon(first, StickerMediaType.Static, trayFile) is
                StickerConvertResult.Success
        if (trayReady) {
            updatePack(newPackId) { it.copy(trayIconPath = trayFile.absolutePath) }
        }

        updatePack(packId) { it.copy(stickerCount = it.stickerCount - rows.size) }
        return newPackId
    }

    /** Marks anything left mid-flight as failed. Called at startup when no
     * operation is running, which can only mean the previous process went
     * away before finishing one. */
    suspend fun failInterruptedOperations(): List<String> {
        // Ids first: the caller needs them to clear the ongoing notifications
        // those operations left behind. A process that dies never reaches
        // stopForeground, so the notification outlives the service that owned
        // it and sits there claiming to be in progress.
        val ids = packDao.unfinishedIds()
        if (ids.isEmpty()) return emptyList()
        packDao.failUnfinished(appContext.getString(R.string.err_operation_interrupted))
        return ids
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        packDao.setPinned(id, pinned)
    }

    suspend fun deletePack(id: String) {
        packDao.delete(id)
        File(appContext.filesDir, "packs/$id").deleteRecursively()
    }

    sealed class DeleteTelegramResult {
        data object Success : DeleteTelegramResult()
        data class Failed(val reason: String) : DeleteTelegramResult()
    }

    /** Deletes the pack's Telegram sticker set (if it has one) and then its
     * local copy. If the Telegram-side deletion fails, the local pack is
     * left untouched so the user doesn't lose their only remaining copy. */
    suspend fun deletePackAndTelegramSet(id: String, backendConfig: TelegramBackendConfig): DeleteTelegramResult {
        val pack = packDao.getPack(id) ?: return DeleteTelegramResult.Failed(appContext.getString(R.string.err_pack_not_found))
        val fullName = pack.telegramSetName
        if (fullName != null) {
            try {
                val backend = TelegramBackendProvider.resolve(backendConfig)
                val result = retryTransientErrors { backend.deleteStickerSet(fullName) }
                if (result is ApiResult.Failure) {
                    return DeleteTelegramResult.Failed(result.error.userMessage)
                }
            } catch (e: Exception) {
                return DeleteTelegramResult.Failed(describeNetworkError(e))
            }
        }
        deletePack(id)
        return DeleteTelegramResult.Success
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
            // Also scrubbed on the way in, but rows written before that was
            // added still hold the raw title, and a stored one would otherwise
            // keep garbling the UI around it forever.
            title = sanitizeTitle(pack.title),
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
            previewStickerPaths = sortedStickers.mapNotNull { it.convertedWhatsappPath }
                .take(PREVIEW_STICKER_LIMIT),
            previewEmojis = sortedStickers
                .flatMap { it.emojis.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(8),
            whatsappAdded = pack.whatsappAdded,
            // Only a Created pack can be "pushed": an Imported one carries a
            // telegramSetName too (it's the duplicate-detection key for the
            // set it came from), and counting its Telegram-converted stickers
            // gave every imported pack a permanent "On Telegram (0/N)" badge
            // for a push that was never attempted and makes no sense for it.
            // This now matches how the push button is already gated.
            telegramPushState = pack.telegramSetName
                ?.takeIf { pack.origin == PackOrigin.Created.name }
                ?.let { fullName ->
                    val pushedCount = sortedStickers.count { it.convertedTelegramPath != null }
                    if (sortedStickers.isNotEmpty() && pushedCount < sortedStickers.size) {
                        TelegramPushState.Partial(fullName, pushedCount, sortedStickers.size)
                    } else {
                        TelegramPushState.Pushed(fullName)
                    }
                } ?: TelegramPushState.NotPushed,
            updateAvailable = pack.updateAvailable,
            telegramSetName = if (pack.origin == PackOrigin.Imported.name) pack.telegramSetName else null,
            importPartIndex = pack.importPartIndex,
            conversionBias = pack.conversionBias
                ?.let { stored -> ConversionBias.entries.firstOrNull { it.name == stored } },
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

    fun extractShortName(input: String): String =
        input.trim().trimEnd('/').substringAfterLast('/')

    private fun sanitizeFileName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "sticker" }

    /** Strips Unicode bidirectional formatting characters out of a pack title.
     *
     * A pack title is remote text that this app then interpolates into its own
     * sentences. A real Telegram pack turned up carrying an unterminated
     * U+2067 RIGHT-TO-LEFT ISOLATE, and it did not stay inside the title: the
     * overwrite prompt rendered as `"<title> is already in My Packs."');
     * ?Overwrite it with this import`, with the app's own question mark
     * dragged to the front of the line. Text that reorders the sentence around
     * it can also reorder it into saying something else, which is the standard
     * bidi spoofing trick and not something to leave in a confirmation dialog.
     *
     * Removing the controls rather than balancing them is deliberate: a title
     * in a right-to-left script still lays out correctly from its own strong
     * characters, so nothing legitimate is lost, and the title travels on into
     * WhatsApp's UI and notifications where this app cannot wrap it in
     * isolates even if it wanted to. */
    private fun sanitizeTitle(raw: String): String =
        raw.filterNot { char ->
            val code = char.code
            code in 0x202A..0x202E || // LRE, RLE, PDF, LRO, RLO
                code in 0x2066..0x2069 || // LRI, RLI, FSI, PDI
                code == 0x200E || code == 0x200F // LRM, RLM
        }.trim().ifBlank { raw.trim() }

    private data class WhatsappConvertedSticker(
        val file: File,
        val type: StickerMediaType,
        val output: File,
        val isAnimated: Boolean,
    )

    /** WhatsApp's own validator rejects a whole pack if even one sticker's
     * WebP frame count disagrees with the pack-level animated/static flag
     * (see StickerPackValidator.validateStickerFile upstream) -- the flag
     * itself is decided from a majority vote across all stickers, so any
     * minority outlier (e.g. one video sticker whose device-side frame
     * extraction only found a single real frame, or a plain static image
     * mixed into an otherwise-animated Telegram set) must be re-encoded to
     * match, not left as the vote's actual per-sticker result. */
    private suspend fun reconcileWhatsappAnimatedMismatches(
        stickers: List<WhatsappConvertedSticker>,
        packIsAnimated: Boolean,
        bias: ConversionBias,
    ) {
        for (sticker in stickers) {
            if (sticker.isAnimated == packIsAnimated) continue
            StickerConversionPipeline.convertForWhatsappForced(
                appContext,
                sticker.file,
                sticker.output,
                sticker.type,
                forceAnimated = packIsAnimated,
                bias = bias,
            )
        }
    }

    companion object {
        /** Sentinel [PackEntity.importPartIndex] for a hand-picked custom
         * selection, distinct from part index 0 (a real "part 1" or a
         * whole, unsplit pack) -- see [StickerPack.importPartIndex]. */
        const val CUSTOM_PART_INDEX = -1

        /** Part index for the animated half of a pack the user chose to split
         * by type. Distinct from any real part so duplicate detection, which
         * keys on set name plus part index, can still tell the two halves
         * apart -- same reasoning as [CUSTOM_PART_INDEX]. */
        const val ANIMATED_SPLIT_PART_INDEX = -2
    }

    private fun describeNetworkError(e: Exception): String = when (e) {
        is java.io.IOException -> appContext.getString(R.string.err_could_not_reach_server)
        else -> e.message ?: appContext.getString(R.string.err_unexpected)
    }

    private suspend fun downloadSticker(
        backend: TelegramBackend,
        setName: String,
        stickerId: String,
        output: File,
        contentTypeHint: String? = null,
        onRetry: suspend (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    ): String? = try {
        retryTransientErrors(onRetry = onRetry) { backend.downloadSticker(setName, stickerId, output, contentTypeHint) }
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
        bias: ConversionBias? = null,
        /** What conversion actually produced. The count written at download
         * time is the intended one, and a sticker the decoder refuses would
         * otherwise leave the pack advertising more than it holds. */
        stickerCount: Int? = null,
    ) {
        updatePack(packId) {
            it.copy(
                status = PackStatus.Ready.name,
                isAnimatedPack = isAnimated,
                stickerCount = stickerCount ?: it.stickerCount,
                trayIconPath = trayIconPath ?: it.trayIconPath,
                warningMessage = warning,
                errorMessage = null,
                // Only meaningful for an animated pack: the knob picks how
                // far the encoder may trade quality for frames, and a static
                // sticker has no frames to trade.
                conversionBias = bias?.name.takeIf { isAnimated },
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

    /** Returns false only when the server positively confirms the user hasn't
     * started a chat with the bot; true when confirmed started; null when the
     * check itself couldn't be completed (network/server error) -- callers
     * should treat null as "proceed anyway" so a flaky verify call never
     * blocks a push that might otherwise succeed. */
    private suspend fun checkUserStartedChat(userId: String, backendConfig: TelegramBackendConfig): Boolean? = try {
        val backend = TelegramBackendProvider.resolve(backendConfig)
        val result = retryTransientErrors { backend.verifyUserStartedChat(userId) }
        (result as? ApiResult.Success)?.value?.started
    } catch (_: Exception) {
        null
    }

    private sealed class PushOneResult {
        data class Success(val fullName: String) : PushOneResult()
        data class Failed(val reason: String) : PushOneResult()
    }

    private suspend fun pushOneSticker(
        backend: TelegramBackend,
        shortName: String,
        userId: String,
        title: String?,
        file: File,
        format: String,
        emojis: List<String>,
        onRetry: suspend (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    ): PushOneResult = try {
        val result = retryTransientErrors(onRetry = onRetry) {
            backend.pushSticker(shortName, userId, title, format, emojis, file)
        }
        when (result) {
            is ApiResult.Failure -> PushOneResult.Failed(result.error.userMessage)
            is ApiResult.Success -> PushOneResult.Success(result.value.name)
        }
    } catch (e: Exception) {
        PushOneResult.Failed(describeNetworkError(e))
    }
}
