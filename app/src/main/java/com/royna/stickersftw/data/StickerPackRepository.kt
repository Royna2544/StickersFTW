package com.royna.stickersftw.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.royna.stickersftw.BuildConfig
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
import com.royna.stickersftw.model.MediaCrop
import com.royna.stickersftw.model.PackOrigin
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind
import com.royna.stickersftw.model.StickerGridItem
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.model.deriveNeedsReconversion
import com.royna.stickersftw.model.deriveTelegramFreshness
import com.royna.stickersftw.model.deriveWhatsappFreshness
import com.royna.stickersftw.model.parseStickerEmojis
import com.royna.stickersftw.model.TelegramFreshnessState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How many sticker paths [StickerPack.previewStickerPaths] carries -- three
 * full rows of the pack detail screen's six-column preview grid. Derived on
 * every read rather than stored, so changing it takes effect for packs that
 * are already imported. The full set lives behind "View all stickers". */
private const val PREVIEW_STICKER_LIMIT = 18
/** Canonical storage shape shared by WhatsApp and Telegram. Each supplied
 * chip may itself contain a legacy comma-joined value, so both forms are
 * accepted while the editor transitions to a real chip list. */
internal fun normalizeStickerEmojis(emojis: List<String>): String {
    val normalized = emojis
        .flatMap { parseStickerEmojis(it).orEmpty() }
        .distinct()
        .take(SizeBudget.MAX_EMOJIS)
    return (normalized.ifEmpty { listOf(SizeBudget.FALLBACK_EMOJI) }).joinToString(",")
}

/** Combines consumer and Business provider answers without letting a known
 * `false` from one client hide a known `true` from the other. */
internal fun combineWhatsappWhitelistStates(consumer: Boolean?, business: Boolean?): Boolean? = when {
    consumer == true || business == true -> true
    consumer == false && business == false -> false
    else -> consumer ?: business
}

internal fun canFinalizePublish(expectedRevision: Int?, currentRevision: Int): Boolean =
    expectedRevision == null || expectedRevision == currentRevision

/** Returns a complete requested order only when it names every current row
 * exactly once. Kept pure so drag/drop validation can be pinned by JVM tests. */
internal fun validatedStickerOrder(currentIds: List<Long>, requestedIds: List<Long>): List<Long>? =
    requestedIds.takeIf {
        it.size == currentIds.size &&
            it.distinct().size == it.size &&
            it.toSet() == currentIds.toSet()
    }

/** Identity of an independent local copy and the stable-row translation used
 * to replay the mutation that originally requested the copy. */
data class ForkPackResult(
    val newPackId: String,
    val rowIdMap: Map<Long, Long>,
)

/** Unifies Room persistence, the network client, the conversion pipeline,
 * and WhatsApp registration behind one API the ViewModel drives. Constructed
 * manually (no DI framework), mirroring the existing SettingsRepository. */
class StickerPackRepository(private val appContext: Context) {
    private val database = AppDatabase.getInstance(appContext)
    private val packDao: PackDao = database.packDao()
    private val stickerDao: StickerDao = database.stickerDao()
    private val pendingEditMutex = Mutex()
    private val pendingEdits = mutableMapOf<String, PendingPackEdit>()

    /** A lightweight reachability check (hits the same cheap /v1/bot route
     * used to show the bot's username) -- deliberately a single attempt with
     * no retry, since this backs interactive "is this server reachable"
     * checks (Settings save, Convert page status) that need a prompt answer,
     * not a resilient background operation. */
    suspend fun pingServer(backendConfig: TelegramBackendConfig): Boolean =
        TelegramBackendProvider.resolve(backendConfig).ping()

    fun observePacks(): Flow<List<StickerPack>> =
        packDao.observePacksWithStickers().map { list -> list.map { it.toUiModel() } }

    /** Every converted sticker in a pack, in stable editable order. */
    fun observePackStickers(packId: String): Flow<List<StickerGridItem>> =
        packDao.observePackWithStickers(packId).map { packWithStickers ->
            packWithStickers?.stickers
                ?.sortedBy { it.position }
                ?.mapNotNull { sticker ->
                    sticker.convertedWhatsappPath?.let { path ->
                        val type = sticker.mediaType()
                        StickerGridItem(
                            rowId = sticker.rowId,
                            position = sticker.position,
                            path = path,
                            emoji = sticker.emojis,
                            isVideo = type == StickerMediaType.Video,
                            isTray = packWithStickers.pack.trayStickerRowId == sticker.rowId,
                            canEditVisual = type != StickerMediaType.AnimatedLottie,
                        )
                    }
                }
                .orEmpty()
        }

    /** Creates an independent Created copy before an upstream-linked pack is
     * edited. Referenced pack assets are staged under a fresh, unreachable
     * directory first. Room exposes the new pack and all of its fresh sticker
     * rows together only after the source snapshot is revalidated.
     *
     * A null result means the title/source was invalid, a required owned asset
     * could not be copied, or the source changed while it was being copied.
     * In every failure case the source is untouched and the fresh directory is
     * removed. */
    suspend fun forkPackForLocalEdits(packId: String, title: String): ForkPackResult? =
        withContext(Dispatchers.IO) {
            val forkTitle = sanitizeTitle(title).takeIf { it.isNotBlank() }
                ?: return@withContext null
            val source = database.withTransaction {
                val pack = packDao.getPack(packId) ?: return@withTransaction null
                val stickers = stickerDao.getStickersOnce(packId).sortedBy { it.position }
                PackForkSource(pack, stickers)
            } ?: return@withContext null
            if (!source.pack.canForkForLocalEdits()) return@withContext null

            val packsRoot = File(appContext.filesDir, "packs").apply { mkdirs() }
            val newPackId = UUID.randomUUID().toString()
            val newPackDir = File(packsRoot, newPackId)
            if (!newPackDir.mkdirs()) return@withContext null

            var committed = false
            try {
                val token = UUID.randomUUID().toString().replace("-", "")
                val stagedStickers = source.stickers.mapIndexed { index, sticker ->
                    val original = copyForkAsset(
                        packsRoot = packsRoot,
                        sourcePath = sticker.originalFilePath,
                        destination = File(newPackDir, "original/$index-$token.bin"),
                    )
                    val whatsapp = copyForkAsset(
                        packsRoot = packsRoot,
                        sourcePath = sticker.convertedWhatsappPath,
                        destination = File(newPackDir, "converted/$index-$token.webp"),
                    )
                    StagedForkSticker(sticker, original, whatsapp)
                }
                val stagedTray = copyForkAsset(
                    packsRoot = packsRoot,
                    sourcePath = source.pack.trayIconPath,
                    destination = File(newPackDir, "tray-$token.webp"),
                )
                val now = System.currentTimeMillis()

                withContext(NonCancellable) {
                    database.withTransaction {
                        val currentPack = packDao.getPack(packId)
                            ?: return@withTransaction null
                        val currentStickers = stickerDao.getStickersOnce(packId)
                            .sortedBy { it.position }
                        if (currentPack != source.pack || currentStickers != source.stickers) {
                            return@withTransaction null
                        }

                        val fork = source.pack.copy(
                            id = newPackId,
                            origin = PackOrigin.Created.name,
                            telegramSetName = null,
                            pushShortName = null,
                            sourceUrl = null,
                            title = forkTitle,
                            publisher = "You",
                            stickerCount = stagedStickers.count { it.whatsappPath != null },
                            status = PackStatus.Ready.name,
                            errorMessage = null,
                            warningMessage = null,
                            trayIconPath = stagedTray,
                            isPinned = false,
                            whatsappAdded = false,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            sourceSignature = null,
                            updateAvailable = false,
                            updateCheckEnabled = false,
                            importPartIndex = 0,
                            sourcePartIndex = null,
                            convertedAppVersionCode = null,
                            convertedAppVersionName = null,
                            imageDataVersion = 1,
                            trayStickerRowId = null,
                            whatsappSyncedDataVersion = null,
                            telegramSyncedDataVersion = null,
                        )
                        packDao.upsert(fork)

                        val rowIdMap = linkedMapOf<Long, Long>()
                        stagedStickers.forEach { staged ->
                            val newRowId = stickerDao.upsert(
                                staged.source.copy(
                                    rowId = 0,
                                    packId = newPackId,
                                    remoteId = null,
                                    remoteStableId = null,
                                    sourceLocalUri = staged.originalPath
                                        ?.let { Uri.fromFile(File(it)).toString() },
                                    originalFilePath = staged.originalPath,
                                    convertedWhatsappPath = staged.whatsappPath,
                                    // This column doubles as the per-row
                                    // successful-push marker. Keeping it would
                                    // make a future publish skip the copied row.
                                    convertedTelegramPath = null,
                                ),
                            )
                            rowIdMap[staged.source.rowId] = newRowId
                        }

                        val mappedTrayRowId = source.pack.trayStickerRowId
                            ?.let(rowIdMap::get)
                        if (mappedTrayRowId != null) {
                            packDao.upsert(fork.copy(trayStickerRowId = mappedTrayRowId))
                        }
                        ForkPackResult(newPackId, rowIdMap.toMap())
                    }.also { result ->
                        if (result != null) committed = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                if (!committed) deleteForkDirectory(packsRoot, newPackDir)
            }
        }

    /** Removes a just-created fork when its requested mutation could not even
     * start. The revision/link checks make this a no-op once any editor action
     * has changed the clone, so a late cancellation can never erase work. */
    suspend fun discardUnmodifiedLocalRemix(packId: String): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val removed = database.withTransaction {
                val pack = packDao.getPack(packId) ?: return@withTransaction false
                val isFreshLocalRemix = pack.origin == PackOrigin.Created.name &&
                    pack.sourceUrl == null &&
                    pack.sourceSignature == null &&
                    pack.telegramSetName == null &&
                    pack.whatsappAdded.not() &&
                    pack.whatsappSyncedDataVersion == null &&
                    pack.telegramSyncedDataVersion == null &&
                    pack.imageDataVersion == 1
                if (!isFreshLocalRemix) return@withTransaction false
                packDao.delete(packId)
                true
            }
            if (removed) {
                val packsRoot = File(appContext.filesDir, "packs")
                deleteForkDirectory(packsRoot, File(packsRoot, packId))
            }
            removed
        }

    /** Loads the durable source and its current non-destructive recipe for
     * the existing crop/range coordinator. TGS sources deliberately return
     * null: replacing them is supported, bitmap-style visual editing is not. */
    suspend fun editableStickerItem(packId: String, rowId: Long): PickedMediaItem? =
        withContext(Dispatchers.IO) {
            val sticker = stickerDao.findByRowIdInPack(packId, rowId) ?: return@withContext null
            if (sticker.mediaType() == StickerMediaType.AnimatedLottie) return@withContext null
            val original = sticker.originalFilePath?.let(::File)?.takeIf(File::exists)
                ?: return@withContext null
            PickedMediaItem(
                uri = Uri.fromFile(original).toString(),
                kind = if (sticker.mediaType() == StickerMediaType.Video) {
                    PickedMediaKind.Video
                } else {
                    PickedMediaKind.Image
                },
                emoji = normalizeStickerEmojis(listOf(sticker.emojis)),
                trimStartMs = sticker.trimStartMs,
                trimDurationMs = sticker.trimDurationMs,
                crop = sticker.mediaCrop(),
            )
        }

    /** Atomically replaces one sticker's source recipe and WhatsApp output.
     * New files have unique names and remain unreachable until the Room
     * transaction swaps both the row and pack revision. */
    fun editSticker(
        packId: String,
        rowId: Long,
        item: PickedMediaItem,
        bias: ConversionBias = ConversionBias.Auto,
    ): Flow<PackOperationProgress> = flow {
        finalizeLastPackEdit(packId)
        val initialPack = packDao.getPack(packId)
        val initialSticker = stickerDao.findByRowIdInPack(packId, rowId)
        if (initialPack == null || initialSticker == null) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_found)))
            return@flow
        }

        val packDir = File(appContext.filesDir, "packs/$packId")
        val token = UUID.randomUUID().toString().replace("-", "")
        val newOriginal = File(packDir, "original/$rowId-$token.bin")
        val newWhatsapp = File(packDir, "converted/$rowId-$token.webp")
        val newTray = if (initialPack.trayStickerRowId == rowId) {
            File(packDir, "tray-$token.webp")
        } else {
            null
        }
        val newFiles = listOfNotNull(newOriginal, newWhatsapp, newTray)
        var committed = false

        try {
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_preparing_media, 1, 1),
                    0.15f,
                    slowFormat = item.kind == PickedMediaKind.Video,
                ),
            )
            if (!copyUriToFile(item.uri, newOriginal)) {
                emit(PackOperationProgress.Failed(appContext.getString(R.string.err_could_not_read_sticker_media)))
                return@flow
            }
            deletePreparedInput(item.uri)

            val type = if (item.kind == PickedMediaKind.Video) StickerMediaType.Video else StickerMediaType.Static
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_converting_whatsapp, 1, 1),
                    0.55f,
                    slowFormat = item.kind == PickedMediaKind.Video,
                ),
            )
            val conversion = StickerConversionPipeline.convertForWhatsappForced(
                appContext,
                newOriginal,
                newWhatsapp,
                type,
                forceAnimated = initialPack.isAnimatedPack,
                bias = bias,
                trimStartMs = item.trimStartMs,
                trimDurationMs = item.trimDurationMs,
                crop = item.crop,
            )
            if (conversion is StickerConvertResult.Failed) {
                emit(PackOperationProgress.Failed(conversion.reason))
                return@flow
            }
            val success = conversion as StickerConvertResult.Success

            if (newTray != null) {
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_building_tray_icon),
                        0.8f,
                        slowFormat = item.kind == PickedMediaKind.Video,
                    ),
                )
                val trayResult = StickerConversionPipeline.buildTrayIcon(
                    newOriginal,
                    type,
                    newTray,
                    trimStartMs = item.trimStartMs,
                    trimDurationMs = item.trimDurationMs,
                    crop = item.crop,
                )
                if (trayResult is StickerConvertResult.Failed) {
                    emit(PackOperationProgress.Failed(trayResult.reason))
                    return@flow
                }
            }

            val replaced = withContext(NonCancellable) {
                database.withTransaction {
                    val currentPack = packDao.getPack(packId) ?: return@withTransaction null
                    val currentSticker = stickerDao.findByRowIdInPack(packId, rowId)
                        ?: return@withTransaction null
                    // A DB-only edit can happen while media is converting. Never
                    // overwrite it with a recipe based on a stale row/revision.
                    if (currentPack.imageDataVersion != initialPack.imageDataVersion ||
                        currentSticker != initialSticker
                    ) {
                        return@withTransaction null
                    }

                    stickerDao.upsert(
                        currentSticker.copy(
                            sniffedContentType = null,
                            sourceLocalUri = Uri.fromFile(newOriginal).toString(),
                            isVideo = item.kind == PickedMediaKind.Video,
                            originalFilePath = newOriginal.absolutePath,
                            convertedWhatsappPath = newWhatsapp.absolutePath,
                            conversionStatus = if (success.warning == null) {
                                "Done"
                            } else {
                                "DoneWithWarning"
                            },
                            conversionError = success.warning,
                            trimStartMs = item.trimStartMs,
                            trimDurationMs = item.trimDurationMs,
                            cropLeft = item.crop?.left,
                            cropTop = item.crop?.top,
                            cropRight = item.crop?.right,
                            cropBottom = item.crop?.bottom,
                        ),
                    )
                    packDao.upsert(
                        currentPack.bumpRevision().copy(
                            trayIconPath = newTray?.absolutePath ?: currentPack.trayIconPath,
                        ),
                    )
                    CommittedVisualEdit(
                        oldSticker = currentSticker,
                        oldTrayPath = currentPack.trayIconPath.takeIf { newTray != null },
                    )
                }.also { edit ->
                    if (edit != null) {
                        // This flag and reclamation must happen in the same
                        // non-cancellable section as the committed swap. A
                        // collector disappearing must never make finally
                        // delete files that Room already points at.
                        committed = true
                        deleteOwnedPackFiles(
                            packDir,
                            listOfNotNull(
                                edit.oldSticker.originalFilePath,
                                edit.oldSticker.convertedWhatsappPath,
                                edit.oldTrayPath,
                            ),
                        )
                    }
                }
            }

            if (replaced == null) {
                emit(PackOperationProgress.Failed(appContext.getString(R.string.err_unexpected)))
                return@flow
            }
            emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_done), 1f))
            emit(PackOperationProgress.Complete(packId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emit(PackOperationProgress.Failed(error.message ?: appContext.getString(R.string.err_unexpected)))
        } finally {
            if (!committed) newFiles.forEach(File::delete)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun updateStickerEmojis(
        packId: String,
        rowId: Long,
        emojis: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        finalizeLastPackEdit(packId)
        val normalized = parseStickerEmojis(emojis.joinToString(" "))
            ?.joinToString(",")
            ?: return@withContext false
        database.withTransaction {
            val pack = packDao.getPack(packId) ?: return@withTransaction false
            val sticker = stickerDao.findByRowIdInPack(packId, rowId) ?: return@withTransaction false
            if (sticker.emojis == normalized) return@withTransaction true
            stickerDao.upsert(sticker.copy(emojis = normalized))
            packDao.upsert(pack.bumpRevision())
            true
        }
    }

    suspend fun updateStickerEmojis(packId: String, rowId: Long, emojis: String): Boolean =
        updateStickerEmojis(packId, rowId, listOf(emojis))

    /** Renders a new 96px tray before exposing it. The ContentProvider maps
     * WhatsApp's stable `tray.webp` request to this versioned stored path. */
    suspend fun setTraySticker(packId: String, rowId: Long): Boolean = withContext(Dispatchers.IO) {
        finalizeLastPackEdit(packId)
        val initialPack = packDao.getPack(packId) ?: return@withContext false
        val initialSticker = stickerDao.findByRowIdInPack(packId, rowId) ?: return@withContext false
        if (initialSticker.convertedWhatsappPath == null) return@withContext false
        if (initialPack.trayStickerRowId == rowId &&
            initialPack.trayIconPath?.let(::File)?.exists() == true
        ) {
            return@withContext true
        }

        val original = initialSticker.originalFilePath?.let(::File)?.takeIf(File::exists)
        val converted = initialSticker.convertedWhatsappPath.let(::File).takeIf(File::exists)
        val source = original ?: converted ?: return@withContext false
        val useOriginalRecipe = source == original
        val type = if (useOriginalRecipe) initialSticker.mediaType() else StickerMediaType.Static
        val packDir = File(appContext.filesDir, "packs/$packId")
        val output = File(
            packDir,
            "tray-${UUID.randomUUID().toString().replace("-", "")}.webp",
        )
        val result = StickerConversionPipeline.buildTrayIcon(
            source,
            type,
            output,
            trimStartMs = initialSticker.trimStartMs.takeIf { useOriginalRecipe } ?: 0L,
            trimDurationMs = initialSticker.trimDurationMs.takeIf { useOriginalRecipe } ?: 0L,
            crop = initialSticker.mediaCrop().takeIf { useOriginalRecipe },
        )
        if (result is StickerConvertResult.Failed) {
            output.delete()
            return@withContext false
        }

        val oldTray = database.withTransaction {
            val currentPack = packDao.getPack(packId) ?: return@withTransaction null
            val currentSticker = stickerDao.findByRowIdInPack(packId, rowId)
                ?: return@withTransaction null
            if (currentPack.imageDataVersion != initialPack.imageDataVersion ||
                currentSticker != initialSticker
            ) {
                return@withTransaction null
            }
            packDao.upsert(
                currentPack.bumpRevision().copy(
                    trayIconPath = output.absolutePath,
                    trayStickerRowId = rowId,
                ),
            )
            currentPack.trayIconPath ?: ""
        }
        if (oldTray == null) {
            output.delete()
            return@withContext false
        }
        deleteOwnedPackFiles(packDir, listOf(oldTray).filter(String::isNotEmpty))
        true
    }

    /** Applies one complete drag result. Hidden failed rows stay behind the
     * visible set, while visible row IDs must be present exactly once. */
    suspend fun reorderStickers(packId: String, orderedRowIds: List<Long>): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            finalizeLastPackEdit(packId)
            var pending: PendingPackEdit.Reorder? = null
            val changed = database.withTransaction {
                val pack = packDao.getPack(packId) ?: return@withTransaction false
                val rows = stickerDao.getStickersOnce(packId).sortedBy { it.position }
                val usable = rows.filter { it.convertedWhatsappPath != null }
                val requested = validatedStickerOrder(usable.map { it.rowId }, orderedRowIds)
                    ?: return@withTransaction false
                if (requested == usable.map { it.rowId }) return@withTransaction true

                val byId = usable.associateBy { it.rowId }
                val reordered = requested.map { byId.getValue(it) } +
                    rows.filter { it.convertedWhatsappPath == null }
                val oldPositions = rows.associate { it.rowId to it.position }
                val updatedPack = pack.bumpRevision()
                stickerDao.upsertAll(
                    reordered.mapIndexed { index, sticker -> sticker.copy(position = index) },
                )
                packDao.upsert(updatedPack)
                pending = PendingPackEdit.Reorder(
                    packId = packId,
                    previousPositions = oldPositions,
                    syncSnapshot = pack.syncSnapshot(),
                    appliedVersion = updatedPack.imageDataVersion,
                )
                true
            }
            pending?.let { edit -> pendingEditMutex.withLock { pendingEdits[packId] = edit } }
            changed
        }

    /** Removes a visible sticker while retaining its files and full row until
     * the Snackbar either restores it or calls [finalizeLastPackEdit]. */
    suspend fun deleteSticker(packId: String, rowId: Long): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            finalizeLastPackEdit(packId)
            var pending: PendingPackEdit.Delete? = null
            val deleted = database.withTransaction {
                val pack = packDao.getPack(packId) ?: return@withTransaction false
                val rows = stickerDao.getStickersOnce(packId).sortedBy { it.position }
                val usable = rows.filter { it.convertedWhatsappPath != null }
                if (usable.size <= SizeBudget.MIN_STICKERS) return@withTransaction false
                val target = usable.firstOrNull { it.rowId == rowId }
                    ?: return@withTransaction false
                val oldPositions = rows.associate { it.rowId to it.position }
                val remaining = rows.filterNot { it.rowId == rowId }
                    .mapIndexed { index, sticker -> sticker.copy(position = index) }
                val updatedPack = pack.bumpRevision().copy(
                    stickerCount = usable.size - 1,
                    trayStickerRowId = pack.trayStickerRowId.takeUnless { it == rowId },
                )
                stickerDao.deleteByRowId(rowId)
                stickerDao.upsertAll(remaining)
                packDao.upsert(updatedPack)
                pending = PendingPackEdit.Delete(
                    packId = packId,
                    deletedSticker = target,
                    previousPositions = oldPositions,
                    previousStickerCount = pack.stickerCount,
                    previousTrayStickerRowId = pack.trayStickerRowId,
                    syncSnapshot = pack.syncSnapshot(),
                    appliedVersion = updatedPack.imageDataVersion,
                )
                true
            }
            pending?.let { edit -> pendingEditMutex.withLock { pendingEdits[packId] = edit } }
            deleted
        }

    suspend fun undoLastPackEdit(packId: String) = withContext(Dispatchers.IO) {
        val pending = pendingEditMutex.withLock { pendingEdits.remove(packId) }
            ?: return@withContext
        val restored = database.withTransaction {
            val pack = packDao.getPack(packId) ?: return@withTransaction false
            if (pack.imageDataVersion != pending.appliedVersion) return@withTransaction false
            val rows = stickerDao.getStickersOnce(packId)
            when (pending) {
                is PendingPackEdit.Reorder -> {
                    if (rows.map { it.rowId }.toSet() != pending.previousPositions.keys) {
                        return@withTransaction false
                    }
                    stickerDao.upsertAll(
                        rows.map { sticker ->
                            sticker.copy(position = pending.previousPositions.getValue(sticker.rowId))
                        },
                    )
                    packDao.upsert(pack.bumpRevision(pending.syncSnapshot))
                }
                is PendingPackEdit.Delete -> {
                    val expectedCurrent = pending.previousPositions.keys - pending.deletedSticker.rowId
                    if (rows.map { it.rowId }.toSet() != expectedCurrent) {
                        return@withTransaction false
                    }
                    val restoredRows = rows.map { sticker ->
                        sticker.copy(position = pending.previousPositions.getValue(sticker.rowId))
                    } + pending.deletedSticker.copy(
                        position = pending.previousPositions.getValue(pending.deletedSticker.rowId),
                    )
                    stickerDao.upsertAll(restoredRows)
                    packDao.upsert(
                        pack.bumpRevision(pending.syncSnapshot).copy(
                            stickerCount = pending.previousStickerCount,
                            trayStickerRowId = pending.previousTrayStickerRowId,
                        ),
                    )
                }
            }
            true
        }
        if (!restored) {
            pendingEditMutex.withLock { pendingEdits.putIfAbsent(packId, pending) }
        }
    }

    suspend fun finalizeLastPackEdit(packId: String) = withContext(Dispatchers.IO) {
        val pending = pendingEditMutex.withLock { pendingEdits.remove(packId) }
            ?: return@withContext
        if (pending is PendingPackEdit.Delete) {
            deleteOwnedPackFiles(
                File(appContext.filesDir, "packs/$packId"),
                listOfNotNull(
                    pending.deletedSticker.originalFilePath,
                    pending.deletedSticker.convertedWhatsappPath,
                    pending.deletedSticker.convertedTelegramPath,
                ),
            )
        }
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
     * from scratch using the same part index or selected subset it was
     * originally imported with. This is a fresh re-import under the same pack
     * id rather than a merge/reconciliation. */
    fun applyPackUpdate(
        packId: String,
        backendConfig: TelegramBackendConfig,
        bias: ConversionBias = ConversionBias.Auto,
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
        val fetched = fetchStickerSet(
            packId,
            backendConfig,
            input,
            force = true,
            preserveReadyPackOnFailure = true,
        ) ?: return@flow
        val (shortNameInput, setDto) = fetched

        val countResult = PackConversionPlanner.applyCountRules(setDto.stickers)
        if (countResult is PlannerResult.Rejected) {
            emit(PackOperationProgress.Failed(countResult.reason))
            return@flow
        }

        val partRanges = PackConversionPlanner.computePartRanges(setDto.stickers.size)
        val currentRows = stickerDao.getStickersOnce(packId)
        val currentIdentities = currentRows.flatMap { row ->
            listOfNotNull(row.remoteStableId, row.remoteId)
        }.toSet()
        val partIndex = pack.importPartIndex
        // Releases before STATIC_SPLIT_PART_INDEX left the static half at its
        // ordinary part number. Pair it with the exact animated title shape
        // the old splitter created; unlike fresh part boundaries, that stored
        // relationship remains stable when Telegram inserts/reorders items.
        val legacyAnimatedTitle = pack.title + appContext.getString(R.string.pack_animated_suffix)
        val legacyAnimatedSibling = if (partIndex >= 0) {
            packDao.getImportedPacksForSet(setName).firstOrNull { sibling ->
                sibling.id != packId &&
                    sibling.importPartIndex == ANIMATED_SPLIT_PART_INDEX &&
                    sibling.title == legacyAnimatedTitle
            }
        } else {
            null
        }
        val isLegacyStaticSplit = legacyAnimatedSibling != null
        val sourcePartSuffix = pack.sourcePartIndex
            ?.takeIf { it in partRanges.indices && partRanges.size > 1 }
            ?.let { " (Part ${it + 1}/${partRanges.size})" }
            .orEmpty()
        val historicalSelectionSuffix = SourceSignature.parse(pack.sourceSignature)
            ?.title
            ?.let(::sanitizeTitle)
            ?.takeIf { baselineTitle -> pack.title.startsWith(baselineTitle) }
            ?.let { baselineTitle -> pack.title.removePrefix(baselineTitle) }
            .orEmpty()
        val stickerDtos: List<StickerDto>
        val titleSuffix: String
        when {
            partIndex == CUSTOM_PART_INDEX ||
                partIndex == ANIMATED_SPLIT_PART_INDEX ||
                partIndex == STATIC_SPLIT_PART_INDEX ||
                isLegacyStaticSplit -> {
                // A custom/split pack is a selected subset, not Telegram part
                // zero. Preserve that exact identity on re-import rather than
                // silently replacing it with an unrelated first 30 stickers.
                stickerDtos = setDto.stickers.filter { dto ->
                    SourceSignature.matchesAnyStoredIdentity(dto, currentIdentities)
                }
                if (stickerDtos.size < SizeBudget.MIN_STICKERS) {
                    emit(
                        PackOperationProgress.Failed(
                            appContext.getString(R.string.err_reimport_selection_missing),
                        ),
                    )
                    return@flow
                }
                titleSuffix = if (partIndex == CUSTOM_PART_INDEX) {
                    " (Custom)"
                } else if (partIndex == ANIMATED_SPLIT_PART_INDEX) {
                    if (pack.sourcePartIndex != null) {
                        sourcePartSuffix + appContext.getString(R.string.pack_animated_suffix)
                    } else {
                        historicalSelectionSuffix.ifEmpty {
                            appContext.getString(R.string.pack_animated_suffix)
                        }
                    }
                } else {
                    if (pack.sourcePartIndex != null) sourcePartSuffix else historicalSelectionSuffix
                }
            }
            else -> {
                if (partIndex !in partRanges.indices) {
                    emit(PackOperationProgress.Failed(appContext.getString(R.string.err_invalid_part)))
                    return@flow
                }
                stickerDtos = setDto.stickers.slice(partRanges[partIndex])
                titleSuffix = if (partRanges.size > 1) {
                    " (Part ${partIndex + 1}/${partRanges.size})"
                } else {
                    ""
                }
            }
        }

        reimportAndReplaceImportedPack(
            startingPack = pack,
            backendConfig = backendConfig,
            input = input,
            shortNameInput = shortNameInput,
            setDto = setDto,
            stickerDtos = stickerDtos,
            titleSuffix = titleSuffix,
            bias = bias,
            committedImportPartIndex = if (isLegacyStaticSplit) {
                STATIC_SPLIT_PART_INDEX
            } else {
                pack.importPartIndex
            },
            committedSourcePartIndex = if (isLegacyStaticSplit) partIndex else pack.sourcePartIndex,
            legacyAnimatedSiblingId = legacyAnimatedSibling?.id,
        )
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_update_failed)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Downloads and converts a Telegram snapshot beside the working pack,
     * then swaps rows and versioned asset paths in one transaction. The
     * existing Ready pack remains served until every replacement succeeds. */
    private suspend fun FlowCollector<PackOperationProgress>.reimportAndReplaceImportedPack(
        startingPack: PackEntity,
        backendConfig: TelegramBackendConfig,
        input: String,
        shortNameInput: String,
        setDto: StickerSetDto,
        stickerDtos: List<StickerDto>,
        titleSuffix: String,
        bias: ConversionBias,
        committedImportPartIndex: Int,
        committedSourcePartIndex: Int?,
        legacyAnimatedSiblingId: String?,
    ) {
        val packId = startingPack.id
        val backend = TelegramBackendProvider.resolve(backendConfig)
        val existingRows = stickerDao.getStickersOnce(packId)
        val packDir = File(appContext.filesDir, "packs/$packId")
        val token = "${BuildConfig.VERSION_CODE}-${UUID.randomUUID()}"
        val originalDir = File(packDir, "original")
        val convertedDir = File(packDir, "converted")
        val stagedFiles = mutableListOf<File>()
        val downloaded = mutableListOf<DownloadedRemoteSticker>()
        val converted = mutableListOf<StagedRemoteReimportSticker>()
        var committed = false

        try {
            for ((index, dto) in stickerDtos.withIndex()) {
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_downloading_sticker, index + 1, stickerDtos.size),
                        0.05f + 0.38f * (index + 1) / stickerDtos.size,
                    ),
                )
                val original = File(originalDir, "reimport-$token-$index.bin")
                stagedFiles += original
                val contentType = downloadSticker(
                    backend = backend,
                    setName = setDto.name,
                    stickerId = dto.id,
                    output = original,
                    contentTypeHint = dto.knownContentType,
                )
                if (contentType == null) {
                    throw ReconversionFailure(appContext.getString(R.string.err_download_failed))
                }
                val type = StickerTypeClassifier.classify(contentType).let { classified ->
                    if (classified == StickerMediaType.Unknown) {
                        StickerTypeClassifier.reclassifyUnknown(original)
                    } else {
                        classified
                    }
                }
                downloaded += DownloadedRemoteSticker(dto, original, type, contentType)
            }

            val slowFormat = downloaded.any {
                it.type == StickerMediaType.Video || it.type == StickerMediaType.AnimatedLottie
            }
            for ((index, item) in downloaded.withIndex()) {
                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(R.string.stage_converting_sticker, index + 1, downloaded.size),
                        0.43f + 0.44f * (index + 1) / downloaded.size,
                        slowFormat = slowFormat,
                    ),
                )
                val output = File(convertedDir, "reimport-$token-$index.webp")
                stagedFiles += output
                when (
                    val result = StickerConversionPipeline.convertForWhatsapp(
                        context = appContext,
                        input = item.original,
                        output = output,
                        stickerType = item.type,
                        bias = bias,
                    )
                ) {
                    is StickerConvertResult.Success -> converted += StagedRemoteReimportSticker(
                        downloaded = item,
                        output = output,
                        warning = result.warning,
                        isAnimated = result.isAnimated,
                    )
                    is StickerConvertResult.Failed -> throw ReconversionFailure(result.reason)
                }
            }

            if (converted.size < SizeBudget.MIN_STICKERS) {
                throw ReconversionFailure(
                    appContext.getString(
                        R.string.err_reimport_too_few_converted,
                        converted.size,
                        SizeBudget.MIN_STICKERS,
                    ),
                )
            }

            val animatedCount = converted.count { it.isAnimated }
            val staticCount = converted.size - animatedCount
            // A re-import replaces one existing pack atomically; it cannot
            // safely pause after staging and create a second identity like a
            // first-time import can. Preserve the established majority
            // policy here and surface the same flattening warning.
            val packIsAnimated = animatedCount >= staticCount && animatedCount > 0
            val reimportWarnings = mutableListOf<String>()
            if (animatedCount > 0 && staticCount > 0) {
                val minority = if (packIsAnimated) staticCount else animatedCount
                reimportWarnings += appContext.resources.getQuantityString(
                    R.plurals.warn_mixed_pack_flattened,
                    minority,
                    minority,
                )
            }
            for (index in converted.indices) {
                val item = converted[index]
                if (item.isAnimated == packIsAnimated) continue
                when (
                    val result = StickerConversionPipeline.convertForWhatsappForced(
                        context = appContext,
                        input = item.downloaded.original,
                        output = item.output,
                        stickerType = item.downloaded.type,
                        forceAnimated = packIsAnimated,
                        bias = bias,
                    )
                ) {
                    is StickerConvertResult.Failed -> throw ReconversionFailure(result.reason)
                    is StickerConvertResult.Success -> converted[index] = item.copy(
                        warning = result.warning ?: item.warning,
                        isAnimated = packIsAnimated,
                    )
                }
            }

            emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_building_tray_icon), 0.92f))
            val previousTrayRow = existingRows
                .firstOrNull { it.rowId == startingPack.trayStickerRowId }
            val previousTrayIdentities = listOfNotNull(
                previousTrayRow?.remoteStableId,
                previousTrayRow?.remoteId,
            ).toSet()
            val traySource = converted.firstOrNull {
                SourceSignature.matchesAnyStoredIdentity(
                    it.downloaded.dto,
                    previousTrayIdentities,
                )
            } ?: converted.first()
            val stagedTray = File(packDir, "tray-reimport-$token.webp")
            stagedFiles += stagedTray
            when (
                val trayResult = StickerConversionPipeline.buildTrayIcon(
                    input = traySource.downloaded.original,
                    stickerType = traySource.downloaded.type,
                    output = stagedTray,
                )
            ) {
                is StickerConvertResult.Failed -> throw ReconversionFailure(trayResult.reason)
                is StickerConvertResult.Success -> Unit
            }

            val swapped = withContext(NonCancellable) {
                val didSwap = database.withTransaction {
                    val currentPack = packDao.getPack(packId) ?: return@withTransaction false
                    if (
                        currentPack.origin != PackOrigin.Imported.name ||
                        currentPack.status != PackStatus.Ready.name ||
                        currentPack.imageDataVersion != startingPack.imageDataVersion
                    ) {
                        return@withTransaction false
                    }
                    val currentRows = stickerDao.getStickersOnce(packId)
                    val expectedAssets = existingRows.associate { row ->
                        row.rowId to (row.originalFilePath to row.convertedWhatsappPath)
                    }
                    val currentAssets = currentRows.associate { row ->
                        row.rowId to (row.originalFilePath to row.convertedWhatsappPath)
                    }
                    if (currentAssets != expectedAssets) return@withTransaction false

                    stickerDao.deleteForPack(packId)
                    val newRowIds = mutableMapOf<String, Long>()
                    for ((position, item) in converted.withIndex()) {
                        val downloadedItem = item.downloaded
                        val rowId = stickerDao.upsert(
                            StickerEntity(
                                packId = packId,
                                remoteId = downloadedItem.dto.id,
                                remoteStableId = downloadedItem.dto.stableId,
                                position = position,
                                emojis = downloadedItem.dto.emoji.orEmpty(),
                                sniffedContentType = downloadedItem.contentType,
                                sourceLocalUri = null,
                                isVideo = downloadedItem.type == StickerMediaType.Video,
                                originalFilePath = downloadedItem.original.absolutePath,
                                convertedWhatsappPath = item.output.absolutePath,
                                convertedTelegramPath = null,
                                conversionStatus = if (item.warning == null) "Done" else "DoneWithWarning",
                                conversionError = item.warning,
                            ),
                        )
                        newRowIds[SourceSignature.identityOf(downloadedItem.dto)] = rowId
                    }
                    val warnings = (reimportWarnings + converted.mapNotNull { it.warning })
                        .distinct()
                        .toMutableList()
                    val warning = warnings.joinToString(" ").ifEmpty { null }
                    legacyAnimatedSiblingId?.let { siblingId ->
                        val sibling = packDao.getPack(siblingId)
                        if (
                            sibling != null &&
                            sibling.telegramSetName == currentPack.telegramSetName &&
                            sibling.importPartIndex == ANIMATED_SPLIT_PART_INDEX
                        ) {
                            packDao.upsert(sibling.copy(sourcePartIndex = committedSourcePartIndex))
                        }
                    }
                    packDao.upsert(
                        currentPack.bumpRevision().copy(
                            telegramSetName = setDto.name,
                            pushShortName = null,
                            sourceUrl = input,
                            title = sanitizeTitle(setDto.title) + titleSuffix,
                            publisher = "@$shortNameInput",
                            stickerCount = converted.size,
                            isAnimatedPack = packIsAnimated,
                            status = PackStatus.Ready.name,
                            errorMessage = null,
                            warningMessage = warning,
                            trayIconPath = stagedTray.absolutePath,
                            trayStickerRowId = newRowIds[SourceSignature.identityOf(traySource.downloaded.dto)],
                            sourceSignature = SourceSignature.compute(setDto),
                            updateAvailable = false,
                            importPartIndex = committedImportPartIndex,
                            sourcePartIndex = committedSourcePartIndex,
                            conversionBias = bias.name.takeIf { packIsAnimated },
                            convertedAppVersionCode = BuildConfig.VERSION_CODE,
                            convertedAppVersionName = BuildConfig.VERSION_NAME,
                        ),
                    )
                    true
                }
                if (didSwap) committed = true
                didSwap
            }
            if (!swapped) {
                throw ReconversionFailure(appContext.getString(R.string.err_pack_changed_during_reconversion))
            }
            // Keep the previous versioned paths until the pack itself is
            // deleted. A WhatsApp provider client can resolve a path just
            // before this transaction and open it just after; immediate
            // cleanup would turn that valid in-flight read into a missing
            // frame/file. Unique names keep every new revision unambiguous.

            emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
            emit(PackOperationProgress.Complete(packId))
        } finally {
            if (!committed) stagedFiles.forEach { it.delete() }
        }
    }

    /** Rebuilds every currently served WhatsApp sticker from the durable
     * originals already stored for an imported pack.
     *
     * New outputs use unique names and are not referenced until the final
     * Room transaction. A decoder/encoder failure, cancellation, or racing
     * edit therefore leaves the existing Ready pack and its revision fully
     * intact. The caller performs the forced Telegram freshness check first;
     * this method deliberately does no network I/O. */
    fun reconvertImportedPack(
        packId: String,
        bias: ConversionBias = ConversionBias.Auto,
    ): Flow<PackOperationProgress> = flow {
        val startingPack = packDao.getPack(packId)
        if (
            startingPack == null ||
            startingPack.origin != PackOrigin.Imported.name ||
            startingPack.status != PackStatus.Ready.name
        ) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_ready_for_reconversion)))
            return@flow
        }

        val startingRows = stickerDao.getStickersOnce(packId)
            .filter { it.convertedWhatsappPath != null }
            .sortedBy { it.position }
        if (startingRows.isEmpty()) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_no_stickers_converted)))
            return@flow
        }

        val packDir = File(appContext.filesDir, "packs/$packId")
        // Historical animated split packs kept their durable originals in
        // the sibling source pack. Reading any app-owned pack original is
        // safe; this method only reads those cross-pack files.
        val packsRoot = runCatching { File(appContext.filesDir, "packs").canonicalFile }.getOrNull()
        val ownedPrefix = packsRoot?.path?.plus(File.separator)
        if (ownedPrefix == null) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_ready_for_reconversion)))
            return@flow
        }

        val token = "${BuildConfig.VERSION_CODE}-${UUID.randomUUID()}"
        val convertedDir = File(packDir, "converted")
        val staged = mutableListOf<StagedReconversionSticker>()
        val stagedFiles = mutableListOf<File>()
        var committed = false

        try {
            val slowFormat = startingRows.any {
                val type = it.mediaType()
                type == StickerMediaType.Video || type == StickerMediaType.AnimatedLottie
            }
            for ((index, row) in startingRows.withIndex()) {
                val original = row.originalFilePath
                    ?.let(::File)
                    ?.let { runCatching { it.canonicalFile }.getOrNull() }
                    ?.takeIf { it.path.startsWith(ownedPrefix) && it.isFile }
                if (original == null) {
                    throw ReconversionFailure(appContext.getString(R.string.err_reconversion_source_missing))
                }

                emit(
                    PackOperationProgress.Progress(
                        appContext.getString(
                            R.string.stage_converting_sticker,
                            index + 1,
                            startingRows.size,
                        ),
                        0.05f + 0.82f * (index + 1) / startingRows.size,
                        slowFormat = slowFormat,
                    ),
                )
                val output = File(convertedDir, "reconvert-$token-${row.rowId}.webp")
                stagedFiles += output
                when (
                    val result = StickerConversionPipeline.convertForWhatsappForced(
                        context = appContext,
                        input = original,
                        output = output,
                        stickerType = row.mediaType(),
                        forceAnimated = startingPack.isAnimatedPack,
                        bias = bias,
                        trimStartMs = row.trimStartMs,
                        trimDurationMs = row.trimDurationMs,
                        crop = row.mediaCrop(),
                    )
                ) {
                    is StickerConvertResult.Success -> staged += StagedReconversionSticker(
                        source = row,
                        output = output,
                        warning = result.warning,
                    )
                    is StickerConvertResult.Failed -> throw ReconversionFailure(result.reason)
                }
            }

            emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_building_tray_icon), 0.92f))
            val traySource = staged.firstOrNull { it.source.rowId == startingPack.trayStickerRowId }
                ?: staged.first()
            val stagedTray = File(packDir, "tray-reconvert-$token.webp")
            stagedFiles += stagedTray
            when (
                val trayResult = StickerConversionPipeline.buildTrayIcon(
                    input = File(traySource.source.originalFilePath!!),
                    stickerType = traySource.source.mediaType(),
                    output = stagedTray,
                    trimStartMs = traySource.source.trimStartMs,
                    trimDurationMs = traySource.source.trimDurationMs,
                    crop = traySource.source.mediaCrop(),
                )
            ) {
                is StickerConvertResult.Failed -> throw ReconversionFailure(trayResult.reason)
                is StickerConvertResult.Success -> Unit
            }

            val swapped = withContext(NonCancellable) {
                val didSwap = database.withTransaction {
                    val currentPack = packDao.getPack(packId) ?: return@withTransaction false
                    if (
                        currentPack.status != PackStatus.Ready.name ||
                        currentPack.origin != PackOrigin.Imported.name ||
                        currentPack.imageDataVersion != startingPack.imageDataVersion
                    ) {
                        return@withTransaction false
                    }
                    val currentById = stickerDao.getStickersOnce(packId).associateBy { it.rowId }
                    if (staged.any { candidate -> currentById[candidate.source.rowId] != candidate.source }) {
                        return@withTransaction false
                    }

                    stickerDao.upsertAll(
                        staged.map { candidate ->
                            candidate.source.copy(
                                convertedWhatsappPath = candidate.output.absolutePath,
                                conversionStatus = if (candidate.warning == null) "Done" else "DoneWithWarning",
                                conversionError = candidate.warning,
                            )
                        },
                    )
                    packDao.upsert(
                        currentPack.bumpRevision().copy(
                            status = PackStatus.Ready.name,
                            errorMessage = null,
                            trayIconPath = stagedTray.absolutePath,
                            trayStickerRowId = traySource.source.rowId,
                            stickerCount = staged.size,
                            conversionBias = bias.name.takeIf { currentPack.isAnimatedPack },
                            convertedAppVersionCode = BuildConfig.VERSION_CODE,
                            convertedAppVersionName = BuildConfig.VERSION_NAME,
                        ),
                    )
                    true
                }
                if (didSwap) committed = true
                didSwap
            }
            if (!swapped) {
                throw ReconversionFailure(appContext.getString(R.string.err_pack_changed_during_reconversion))
            }
            // From this point the provider resolves only the new unique file
            // names. Mark committed before cleanup so cancellation cannot
            // remove assets that Room already references.
            // See the re-import path above: old revision files stay readable
            // for provider opens already in flight. Recursive pack deletion
            // remains the eventual cleanup boundary.

            emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
            emit(PackOperationProgress.Complete(packId))
        } finally {
            if (!committed) stagedFiles.forEach { it.delete() }
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(
            PackOperationProgress.Failed(
                error.message ?: appContext.getString(R.string.err_reconversion_failed),
            ),
        )
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
            if (SourceSignature.matches(dto, pack.sourceSignature)) {
                markSourceCurrentIfUnchanged(pack, dto, freshSignature)
            } else {
                packDao.setUpdateAvailableIfUnchanged(
                    pack.id,
                    true,
                    pack.sourceSignature,
                    pack.imageDataVersion,
                )
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
        val changed = !SourceSignature.matches(dto, pack.sourceSignature)
        val recorded = if (changed) {
            packDao.setUpdateAvailableIfUnchanged(
                packId,
                true,
                pack.sourceSignature,
                pack.imageDataVersion,
            ) == 1
        } else {
            markSourceCurrentIfUnchanged(pack, dto, freshSignature)
        }
        if (!recorded) {
            return ForceRefreshResult.Failed(
                appContext.getString(R.string.err_pack_changed_during_refresh),
            )
        }
        return if (changed) {
            ForceRefreshResult.UpdateAvailable
        } else {
            ForceRefreshResult.UpToDate
        }
    }

    /** Commits a freshness result only if the pack still matches the snapshot
     * that initiated the network call. When locator-based legacy signatures
     * match, also teach historical rows their stable Telegram identities so
     * later `file_id` rotations cannot lose custom/split selections. */
    internal suspend fun markSourceCurrentIfUnchanged(
        snapshot: PackEntity,
        dto: StickerSetDto,
        freshSignature: String,
    ): Boolean = database.withTransaction {
        val updated = packDao.markSourceCurrentIfUnchanged(
            snapshot.id,
            freshSignature,
            snapshot.sourceSignature,
            snapshot.imageDataVersion,
        )
        if (updated != 1) return@withTransaction false
        dto.stickers.forEach { sticker ->
            sticker.stableId?.let { stableId ->
                stickerDao.setRemoteStableId(snapshot.id, sticker.id, stableId)
            }
        }
        true
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
        force: Boolean = false,
        preserveReadyPackOnFailure: Boolean = false,
    ): Pair<String, StickerSetDto>? {
        suspend fun reportFailure(message: String) {
            if (preserveReadyPackOnFailure) {
                emit(PackOperationProgress.Failed(message))
            } else {
                failImport(packId, message)
            }
        }
        val shortNameInput = extractShortName(input)
        if (shortNameInput.isBlank()) {
            reportFailure(appContext.getString(R.string.err_enter_pack_link))
            return null
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_reading_metadata), 0.05f))
        val backend = TelegramBackendProvider.resolve(backendConfig)
        val result = try {
            retryTransientErrors(
                onRetry = { attempt, max ->
                    emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_retrying, attempt, max), 0.05f))
                },
            ) { backend.getSet(shortNameInput, force = force) }
        } catch (e: Exception) {
            reportFailure(describeNetworkError(e))
            return null
        }
        val setDto = when (result) {
            is ApiResult.Failure -> {
                reportFailure(result.error.userMessage)
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
                sourcePartIndex = partIndex.takeIf { it >= 0 },
                // Carried over rather than reset: this row is being rebuilt
                // under an id WhatsApp may already have cached. finalizePackReady
                // moves it once the new content is actually on disk.
                imageDataVersion = existing?.imageDataVersion ?: 1,
                convertedAppVersionCode = existing?.convertedAppVersionCode,
                convertedAppVersionName = existing?.convertedAppVersionName,
            ),
        )
        stickerDao.upsertAll(
            stickerDtos.mapIndexed { index, dto ->
                StickerEntity(
                    packId = packId,
                    remoteId = dto.id,
                    remoteStableId = dto.stableId,
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
                val createdSplit = splitAnimatedIntoOwnPack(packId, convertedForFixup, bias)
                if (createdSplit != null) {
                    splitPackId = createdSplit
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
        val trayStickerRowId = if (trayReady) {
            stickerDao.getStickersOnce(packId)
                .sortedBy { it.position }
                .firstOrNull { it.convertedWhatsappPath != null }
                ?.rowId
        } else {
            null
        }

        finalizePackReady(
            packId,
            packIsAnimated,
            trayFile.absolutePath.takeIf { trayReady },
            warning = warnings.joinToString(" ").ifEmpty { null },
            bias = bias,
            stickerCount = convertedCount,
            trayStickerRowId = trayStickerRowId,
            convertedAppVersionCode = BuildConfig.VERSION_CODE,
            convertedAppVersionName = BuildConfig.VERSION_NAME,
        )

        emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
        emit(PackOperationProgress.Complete(packId, splitPackId))
    }

    // ---- Create (local media -> Telegram push and/or WhatsApp) ------------

    /** How many more stickers [packId] can take before it hits WhatsApp's cap.
     *
     * Counts stickers that actually converted, not rows: a failed import leaves
     * its row behind to record what was lost, and those are neither served to
     * WhatsApp nor counted against the pack, so they must not eat capacity
     * either. */
    suspend fun remainingCapacity(packId: String): Int =
        SizeBudget.MAX_STICKERS - stickerDao.getStickersOnce(packId).count { it.convertedWhatsappPath != null }

    /** Appends locally picked media to a pack that already exists.
     *
     * Deliberately not [publishPack] with extra rows: that converts every
     * sticker in the pack every time, so adding one to a 29-sticker pack would
     * re-run 30 conversions and cost minutes. Only the rows added here are
     * touched; everything already converted is left exactly as it is.
     *
     * The pack's animated/static decision is already made and WhatsApp accepts
     * a pack that is all one or all the other, so new stickers are forced to
     * match it rather than being allowed to change it -- a still added to an
     * animated pack is padded to a two-frame loop, and a clip added to a still
     * pack keeps only its first frame. */
    fun addStickersToPack(
        packId: String,
        items: List<PickedMediaItem>,
        bias: ConversionBias = ConversionBias.Auto,
    ): Flow<PackOperationProgress> = flow {
        val pack = packDao.getPack(packId) ?: run {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_not_found)))
            return@flow
        }
        if (items.isEmpty()) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_no_stickers)))
            return@flow
        }

        val existing = stickerDao.getStickersOnce(packId)
        val existingUsable = existing.count { it.convertedWhatsappPath != null }
        val remaining = SizeBudget.MAX_STICKERS - existingUsable
        if (items.size > remaining) {
            val message = appContext.resources.getQuantityString(
                R.plurals.err_pack_capacity,
                remaining,
                remaining,
            )
            emit(PackOperationProgress.Failed(message))
            return@flow
        }

        val packDir = File(appContext.filesDir, "packs/$packId")
        val startPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        // Inserted one at a time for the generated row id, which names both the
        // cached original and the converted output.
        val newRowIds = items.mapIndexed { index, item ->
            stickerDao.upsert(
                StickerEntity(
                    packId = packId,
                    remoteId = null,
                    position = startPosition + index,
                    emojis = item.emoji,
                    sniffedContentType = null,
                    sourceLocalUri = item.uri,
                    isVideo = item.kind == PickedMediaKind.Video,
                    originalFilePath = null,
                    convertedWhatsappPath = null,
                    convertedTelegramPath = null,
                    conversionStatus = "Pending",
                    conversionError = null,
                    trimStartMs = item.trimStartMs,
                    trimDurationMs = item.trimDurationMs,
                    cropLeft = item.crop?.left,
                    cropTop = item.crop?.top,
                    cropRight = item.crop?.right,
                    cropBottom = item.crop?.bottom,
                ),
            )
        }

        val slowFormat = items.any { it.kind == PickedMediaKind.Video }
        var added = 0
        for ((index, rowId) in newRowIds.withIndex()) {
            val item = items[index]
            emit(
                PackOperationProgress.Progress(
                    appContext.getString(R.string.stage_converting_whatsapp, index + 1, items.size),
                    0.1f + 0.8f * (index + 1) / items.size,
                    slowFormat = slowFormat,
                ),
            )

            val original = File(packDir, "original/$rowId.bin")
            // A sticker that fails here has its row removed rather than kept as
            // Failed. On an import the row is worth keeping -- it records which
            // upstream sticker was lost -- but nothing refers back to a local
            // pick, so a kept row would just sit in the pack forever. The count
            // of dropped ones still reaches the user as the pack's warning.
            if (!copyUriToFile(item.uri, original)) {
                stickerDao.deleteByRowId(rowId)
                continue
            }
            // The operation now owns a durable source. Stop pointing Room at
            // the temporary picker/share copy before reclaiming that cache.
            updateStickerByRowId(rowId) {
                it.copy(
                    sourceLocalUri = Uri.fromFile(original).toString(),
                    originalFilePath = original.absolutePath,
                )
            }
            deletePreparedInput(item.uri)

            val type = if (item.kind == PickedMediaKind.Video) StickerMediaType.Video else StickerMediaType.Static
            val output = File(packDir, "converted/$rowId.webp")
            when (
                val result = StickerConversionPipeline.convertForWhatsappForced(
                    appContext,
                    original,
                    output,
                    type,
                    forceAnimated = pack.isAnimatedPack,
                    bias = bias,
                    trimStartMs = item.trimStartMs,
                    trimDurationMs = item.trimDurationMs,
                    crop = item.crop,
                )
            ) {
                is StickerConvertResult.Success -> {
                    added++
                    updateStickerByRowId(rowId) {
                        it.copy(
                            originalFilePath = original.absolutePath,
                            convertedWhatsappPath = output.absolutePath,
                            conversionStatus = if (result.warning != null) "DoneWithWarning" else "Done",
                            conversionError = result.warning,
                        )
                    }
                }
                is StickerConvertResult.Failed -> {
                    stickerDao.deleteByRowId(rowId)
                    original.delete()
                }
            }
        }

        if (added == 0) {
            // The pack itself was never touched and is still usable, so this
            // fails the operation without failing the pack.
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_no_stickers_converted)))
            return@flow
        }

        val dropped = items.size - added
        updatePack(packId) {
            it.bumpRevision().copy(
                stickerCount = existingUsable + added,
                warningMessage = if (dropped > 0) {
                    appContext.resources.getQuantityString(R.plurals.warn_stickers_dropped, dropped, dropped)
                } else {
                    null
                },
                errorMessage = null,
            )
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.pack_status_ready), 1f))
        emit(PackOperationProgress.Complete(packId, null))
    }.catch { e ->
        emit(PackOperationProgress.Failed(e.message ?: appContext.getString(R.string.err_import_failed)))
    }.flowOn(Dispatchers.IO)

    suspend fun createPack(
        items: List<PickedMediaItem>,
        title: String,
        shortName: String,
        packId: String = UUID.randomUUID().toString(),
    ): String {
        val now = System.currentTimeMillis()
        database.withTransaction {
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
                        trimStartMs = item.trimStartMs,
                        trimDurationMs = item.trimDurationMs,
                        cropLeft = item.crop?.left,
                        cropTop = item.crop?.top,
                        cropRight = item.crop?.right,
                        cropBottom = item.crop?.bottom,
                    )
                },
            )
        }
        return packId
    }

    /** Removes a Create row that never reached the foreground service. The
     * sticker recipes still point at preparation-cache inputs owned by the
     * caller, so unlike conversion cleanup this deliberately touches no
     * files and lets the same submission retry safely. */
    suspend fun discardUnstartedCreatedPack(packId: String): Boolean = database.withTransaction {
        val pack = packDao.getPack(packId) ?: return@withTransaction false
        if (pack.origin != PackOrigin.Created.name || pack.status != PackStatus.Building.name) {
            return@withTransaction false
        }
        packDao.delete(packId)
        true
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
        val publishRevision = pack.imageDataVersion
        val stickers = stickerDao.getStickersOnce(packId)
        if (stickers.isEmpty()) {
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_pack_no_stickers)))
            return@flow
        }
        if (
            pushToTelegram &&
            deriveTelegramFreshness(
                origin = PackOrigin.valueOf(pack.origin),
                imageDataVersion = pack.imageDataVersion,
                syncedDataVersion = pack.telegramSyncedDataVersion,
                hasTelegramSet = pack.telegramSetName != null,
                pushedStickerCount = stickers.count { it.convertedTelegramPath != null },
                totalStickerCount = stickers.size,
            ) == TelegramFreshnessState.OutOfDate
        ) {
            // This release can create/retry a set, but cannot replace remote
            // stickers. Appending current rows to a stale set would silently
            // mix two local revisions, so enforce the UI rule here too.
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_telegram_set_out_of_date)))
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
                updateStickerByRowId(sticker.rowId) {
                    it.copy(
                        sourceLocalUri = Uri.fromFile(cacheFile).toString(),
                        originalFilePath = cacheFile.absolutePath,
                    )
                }
                deletePreparedInput(uriString)
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
        var firstConvertedSticker: StickerEntity? = null
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
                    val result = StickerConversionPipeline.convertForWhatsapp(
                        appContext,
                        file,
                        output,
                        type,
                        bias,
                        trimStartMs = sticker.trimStartMs,
                        trimDurationMs = sticker.trimDurationMs,
                        crop = sticker.mediaCrop(),
                    )
                ) {
                    is StickerConvertResult.Success -> {
                        whatsappConvertedCount++
                        if (result.isAnimated) animatedCount++ else staticCount++
                        convertedForFixup.add(
                            WhatsappConvertedSticker(
                                file,
                                type,
                                output,
                                result.isAnimated,
                                sticker.trimStartMs,
                                sticker.trimDurationMs,
                                sticker.mediaCrop(),
                            ),
                        )
                        if (firstConvertedFile == null) {
                            firstConvertedFile = file
                            firstConvertedType = type
                            firstConvertedSticker = sticker
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
                        when (
                            val convertResult = StickerConversionPipeline.convertForTelegram(
                                file,
                                telegramOutput,
                                isVideo = sticker.isVideo,
                                trimStartMs = sticker.trimStartMs,
                                trimDurationMs = sticker.trimDurationMs,
                                crop = sticker.mediaCrop(),
                            )
                        ) {
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
                                        persistTelegramPushSuccess(
                                            packId = packId,
                                            rowId = sticker.rowId,
                                            convertedPath = convertResult.convertedPath,
                                            fullSetName = pushResult.fullName,
                                            representedRevision = publishRevision,
                                        )
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

        val acknowledgeTelegramAtFinalize = pushToTelegram &&
            telegramPushedFullName != null &&
            telegramPushedCount > 0
        val finalizedRevision = when {
            addToWhatsapp && whatsappConvertedCount > 0 -> {
                emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_building_tray_icon), 0.92f))
                val trayFile = File(packDir, "tray.webp")
                val trayReady = firstConvertedFile != null && firstConvertedType != null &&
                    StickerConversionPipeline.buildTrayIcon(
                        firstConvertedFile,
                        firstConvertedType,
                        trayFile,
                        trimStartMs = firstConvertedSticker?.trimStartMs ?: 0L,
                        trimDurationMs = firstConvertedSticker?.trimDurationMs ?: 0L,
                        crop = firstConvertedSticker?.mediaCrop(),
                    ) is StickerConvertResult.Success
                finalizePackReady(
                    packId,
                    packIsAnimated,
                    trayFile.absolutePath.takeIf { trayReady },
                    telegramPushWarning,
                    bias,
                    trayStickerRowId = firstConvertedSticker?.rowId.takeIf { trayReady },
                    expectedRevision = publishRevision,
                    acknowledgeTelegram = acknowledgeTelegramAtFinalize,
                )
            }
            pushToTelegram && telegramPushedFullName != null -> {
                finalizePackReady(
                    packId,
                    packIsAnimated,
                    pack.trayIconPath,
                    telegramPushWarning,
                    bias,
                    bumpContentRevision = false,
                    expectedRevision = publishRevision,
                    acknowledgeTelegram = acknowledgeTelegramAtFinalize,
                )
            }
            else -> {
                val reason = telegramPushWarning ?: appContext.getString(R.string.err_nothing_published)
                finalizePackFailed(packId, reason)
                emit(PackOperationProgress.Failed(reason))
                return@flow
            }
        }

        if (finalizedRevision == null) {
            // A local edit won the race. The Telegram set name (when one was
            // created) already carries the revision actually pushed, but the
            // newer local pack must neither be overwritten nor acknowledged.
            emit(PackOperationProgress.Failed(appContext.getString(R.string.err_nothing_published)))
            return@flow
        }

        emit(PackOperationProgress.Progress(appContext.getString(R.string.stage_done), 1f))
        emit(PackOperationProgress.Complete(packId))
    }.catch { e ->
        val message = e.message ?: appContext.getString(R.string.err_publish_failed)
        finalizePackFailed(packId, message)
        emit(PackOperationProgress.Failed(message))
    }.flowOn(Dispatchers.IO)

    /** Commits the first successful remote sticker's marker together with the
     * set identity and represented revision. A process death can therefore
     * leave neither write, never a marker that retry mistakes for a known set.
     * If a concurrent edit removed the row, the identity still commits with
     * the old represented revision, making the pack OutOfDate instead of
     * retryable against an unknown remote set. */
    internal suspend fun persistTelegramPushSuccess(
        packId: String,
        rowId: Long,
        convertedPath: String,
        fullSetName: String,
        representedRevision: Int,
    ): Boolean = database.withTransaction {
        packDao.getPack(packId) ?: return@withTransaction false
        val sticker = stickerDao.findByRowIdInPack(packId, rowId)
        packDao.setTelegramSetName(
            id = packId,
            fullName = fullSetName,
            representedRevision = representedRevision,
            now = System.currentTimeMillis(),
        )
        if (sticker == null) return@withTransaction false
        stickerDao.upsert(sticker.copy(convertedTelegramPath = convertedPath))
        true
    }

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

        if (SourceSignature.matches(dto, pack.sourceSignature)) return PackUpdateDiffResult.UpToDate
        val before = SourceSignature.parse(pack.sourceSignature) ?: return PackUpdateDiffResult.NoBaseline

        val beforeById = before.stickers.associateBy { it.id }
        val afterById = dto.stickers.associate { SourceSignature.identityOf(it) to it.emoji.orEmpty() }

        return PackUpdateDiffResult.Loaded(
            PackUpdateDiff(
                titleBefore = before.title,
                titleAfter = dto.title,
                added = dto.stickers
                    .filter { SourceSignature.identityOf(it) !in beforeById }
                    .map { StickerEntry(SourceSignature.identityOf(it), it.emoji.orEmpty()) },
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

    /** Copies the animated stickers of a mixed pack into a pack of their own,
     * leaving the static ones in the source pack. Both halves receive subset
     * sentinels so a later Telegram re-import preserves their exact identities
     * instead of expanding either half back to the full source slice.
     *
     * Files are staged by copy and the two row sets change in one transaction.
     * This also puts originals beneath the split pack, which lets future app
     * versions rebuild it even after the source half has been deleted. */
    private suspend fun splitAnimatedIntoOwnPack(
        packId: String,
        converted: List<WhatsappConvertedSticker>,
        bias: ConversionBias,
    ): String? {
        val source = packDao.getPack(packId) ?: return null
        val animated = converted.filter { it.isAnimated }
        if (animated.isEmpty()) return null

        val animatedOutputPaths = animated.map { it.output.absolutePath }.toSet()
        val rows = stickerDao.getStickersOnce(packId)
            .filter { it.convertedWhatsappPath in animatedOutputPaths }
            .sortedBy { it.position }
        if (rows.size != animated.size) return null

        val newPackId = UUID.randomUUID().toString()
        val newDir = File(appContext.filesDir, "packs/$newPackId")
        val newOriginalDir = File(newDir, "original")
        val newConvertedDir = File(newDir, "converted")
        val originalPaths = mutableMapOf<Long, String>()
        val convertedPaths = mutableMapOf<Long, String>()
        var committed = false

        try {
            for (row in rows) {
                val original = row.originalFilePath?.let(::File)?.takeIf { it.isFile }
                    ?: return null
                val convertedFile = row.convertedWhatsappPath?.let(::File)?.takeIf { it.isFile }
                    ?: return null
                val originalTarget = File(
                    newOriginalDir,
                    "${row.rowId}-${sanitizeFileName(original.name)}",
                )
                val convertedTarget = File(newConvertedDir, "${row.rowId}.webp")
                originalTarget.parentFile?.mkdirs()
                convertedTarget.parentFile?.mkdirs()
                original.copyTo(originalTarget, overwrite = false)
                convertedFile.copyTo(convertedTarget, overwrite = false)
                originalPaths[row.rowId] = originalTarget.absolutePath
                convertedPaths[row.rowId] = convertedTarget.absolutePath
            }

            val firstConverted = rows.firstOrNull()?.rowId?.let(convertedPaths::get)?.let(::File)
            val trayFile = File(newDir, "tray.webp")
            val trayReady = firstConverted != null &&
                StickerConversionPipeline.buildTrayIcon(
                    firstConverted,
                    StickerMediaType.Static,
                    trayFile,
                ) is StickerConvertResult.Success
            val now = System.currentTimeMillis()

            val splitCreated = withContext(NonCancellable) {
                val didCreate = database.withTransaction {
                    val currentSource = packDao.getPack(packId) ?: return@withTransaction false
                    if (currentSource.imageDataVersion != source.imageDataVersion) {
                        return@withTransaction false
                    }
                    val currentRows = stickerDao.getStickersOnce(packId).associateBy { it.rowId }
                    if (rows.any { currentRows[it.rowId] != it }) return@withTransaction false

                    val splitPack = source.copy(
                        id = newPackId,
                        title = source.title + appContext.getString(R.string.pack_animated_suffix),
                        stickerCount = rows.size,
                        isAnimatedPack = true,
                        status = PackStatus.Ready.name,
                        trayIconPath = trayFile.absolutePath.takeIf { trayReady },
                        trayStickerRowId = null,
                        whatsappAdded = false,
                        whatsappSyncedDataVersion = null,
                        telegramSyncedDataVersion = null,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                        importPartIndex = ANIMATED_SPLIT_PART_INDEX,
                        sourcePartIndex = source.sourcePartIndex
                            ?: source.importPartIndex.takeIf { it >= 0 },
                        conversionBias = bias.name,
                        convertedAppVersionCode = BuildConfig.VERSION_CODE,
                        convertedAppVersionName = BuildConfig.VERSION_NAME,
                    )
                    packDao.upsert(splitPack)
                    var firstRowId: Long? = null
                    rows.forEachIndexed { index, row ->
                        val newRowId = stickerDao.upsert(
                            row.copy(
                                rowId = 0,
                                packId = newPackId,
                                position = index,
                                originalFilePath = originalPaths[row.rowId],
                                convertedWhatsappPath = convertedPaths[row.rowId],
                            ),
                        )
                        if (firstRowId == null) firstRowId = newRowId
                    }
                    if (trayReady) {
                        packDao.upsert(splitPack.copy(trayStickerRowId = firstRowId))
                    }
                    rows.forEach { stickerDao.deleteByRowId(it.rowId) }
                    packDao.upsert(
                        currentSource.copy(
                            stickerCount = currentSource.stickerCount - rows.size,
                            importPartIndex = STATIC_SPLIT_PART_INDEX,
                            sourcePartIndex = currentSource.sourcePartIndex
                                ?: currentSource.importPartIndex.takeIf { it >= 0 },
                            updatedAtMillis = now,
                        ),
                    )
                    true
                }
                if (didCreate) committed = true
                didCreate
            }
            if (!splitCreated) return null

            // The source pack is not provider-visible until its surrounding
            // import finalizes, and the split rows already point at copies.
            deleteOwnedPackFiles(
                File(appContext.filesDir, "packs/$packId"),
                rows.flatMap { listOfNotNull(it.originalFilePath, it.convertedWhatsappPath) },
            )
            return newPackId
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        } finally {
            if (!committed) deleteForkDirectory(File(appContext.filesDir, "packs"), newDir)
        }
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
        readWhatsappWhitelistState(id)?.let { added ->
            // Passive discovery updates presence only. In particular, seeing
            // an edited pack still whitelisted must leave its old revision
            // acknowledgement stale.
            packDao.setWhatsappAdded(id, added)
        }
    }

    suspend fun refreshWhatsappAdded(ids: Collection<String>) {
        ids.distinct().forEach { refreshWhatsappAdded(it) }
    }

    /** Acknowledges the exact revision accepted by an explicit
     * Add-to-WhatsApp flow. The activity result itself is not trustworthy, so
     * the launched client's whitelist and captured revision are both checked
     * before advancing the acknowledgement. */
    suspend fun acknowledgeWhatsappInstall(
        packId: String,
        expectedRevision: Int,
        business: Boolean,
    ): Boolean {
        val authority = WhatsAppContract.authorityFor(appContext)
        return when (WhatsAppWhitelistChecker.isWhitelisted(appContext, authority, packId, business)) {
            true -> acknowledgeWhitelistedWhatsappInstall(packId, expectedRevision)
            false, null -> {
                // A canceled/unknown result for the launched target says
                // nothing about the other WhatsApp client. Recompute combined
                // passive presence instead of erasing it.
                refreshWhatsappAdded(packId)
                false
            }
        }
    }

    internal suspend fun acknowledgeWhitelistedWhatsappInstall(
        packId: String,
        expectedRevision: Int,
    ): Boolean {
        val acknowledged = packDao.acknowledgeWhatsappInstall(packId, expectedRevision) > 0
        if (!acknowledged) {
            // WhatsApp did accept the launched pack, but local content changed
            // while its activity was open. Preserve presence without claiming
            // the later revision was transferred.
            packDao.setWhatsappAdded(packId, true)
        }
        return acknowledged
    }

    private suspend fun readWhatsappWhitelistState(id: String): Boolean? {
        val authority = WhatsAppContract.authorityFor(appContext)
        val consumer = WhatsAppWhitelistChecker.isWhitelisted(appContext, authority, id, business = false)
        val business = WhatsAppWhitelistChecker.isWhitelisted(appContext, authority, id, business = true)
        return combineWhatsappWhitelistStates(consumer, business)
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
        val origin = PackOrigin.valueOf(pack.origin)
        val status = PackStatus.valueOf(pack.status)
        val telegramPushedCount = sortedStickers.count { it.convertedTelegramPath != null }
        return StickerPack(
            id = pack.id,
            // Also scrubbed on the way in, but rows written before that was
            // added still hold the raw title, and a stored one would otherwise
            // keep garbling the UI around it forever.
            title = sanitizeTitle(pack.title),
            author = pack.publisher,
            origin = origin,
            stickerCount = pack.stickerCount,
            isAnimated = pack.isAnimatedPack,
            imageDataVersion = pack.imageDataVersion,
            isPinned = pack.isPinned,
            updatedLabel = formatUpdatedLabel(pack.updatedAtMillis),
            sourceUrl = pack.sourceUrl,
            status = status,
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
            whatsappFreshness = deriveWhatsappFreshness(
                whatsappAdded = pack.whatsappAdded,
                imageDataVersion = pack.imageDataVersion,
                syncedDataVersion = pack.whatsappSyncedDataVersion,
            ),
            telegramFreshness = deriveTelegramFreshness(
                origin = origin,
                imageDataVersion = pack.imageDataVersion,
                syncedDataVersion = pack.telegramSyncedDataVersion,
                hasTelegramSet = pack.telegramSetName != null,
                pushedStickerCount = telegramPushedCount,
                totalStickerCount = sortedStickers.size,
            ),
            updateAvailable = pack.updateAvailable,
            telegramSetName = if (pack.origin == PackOrigin.Imported.name) pack.telegramSetName else null,
            importPartIndex = pack.importPartIndex,
            sourcePartIndex = pack.sourcePartIndex,
            conversionBias = pack.conversionBias
                ?.let { stored -> ConversionBias.entries.firstOrNull { it.name == stored } },
            convertedAppVersionCode = pack.convertedAppVersionCode,
            convertedAppVersionName = pack.convertedAppVersionName,
            needsReconversion = deriveNeedsReconversion(
                origin = origin,
                status = status,
                convertedAppVersionCode = pack.convertedAppVersionCode,
                currentAppVersionCode = BuildConfig.VERSION_CODE,
            ),
            requiresLocalRemix = pack.origin == PackOrigin.Imported.name ||
                pack.sourceUrl != null ||
                pack.sourceSignature != null,
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
        val trimStartMs: Long = 0L,
        val trimDurationMs: Long = 0L,
        val crop: MediaCrop? = null,
    )

    private data class StagedReconversionSticker(
        val source: StickerEntity,
        val output: File,
        val warning: String?,
    )

    private data class DownloadedRemoteSticker(
        val dto: StickerDto,
        val original: File,
        val type: StickerMediaType,
        val contentType: String,
    )

    private data class StagedRemoteReimportSticker(
        val downloaded: DownloadedRemoteSticker,
        val output: File,
        val warning: String?,
        val isAnimated: Boolean,
    )

    private class ReconversionFailure(message: String) : Exception(message)

    private data class PackForkSource(
        val pack: PackEntity,
        val stickers: List<StickerEntity>,
    )

    private data class StagedForkSticker(
        val source: StickerEntity,
        val originalPath: String?,
        val whatsappPath: String?,
    )

    private data class CommittedVisualEdit(
        val oldSticker: StickerEntity,
        val oldTrayPath: String?,
    )

    private data class TargetSyncSnapshot(
        val whatsappWasCurrent: Boolean,
        val telegramWasCurrent: Boolean,
    )

    private sealed interface PendingPackEdit {
        val packId: String
        val syncSnapshot: TargetSyncSnapshot
        val appliedVersion: Int

        data class Reorder(
            override val packId: String,
            val previousPositions: Map<Long, Int>,
            override val syncSnapshot: TargetSyncSnapshot,
            override val appliedVersion: Int,
        ) : PendingPackEdit

        data class Delete(
            override val packId: String,
            val deletedSticker: StickerEntity,
            val previousPositions: Map<Long, Int>,
            val previousStickerCount: Int,
            val previousTrayStickerRowId: Long?,
            override val syncSnapshot: TargetSyncSnapshot,
            override val appliedVersion: Int,
        ) : PendingPackEdit
    }

    private fun PackEntity.telegramRevisionBeforeMutation(): Int? =
        telegramSyncedDataVersion ?: imageDataVersion.takeIf {
            origin == PackOrigin.Created.name && telegramSetName != null
        }

    private fun PackEntity.syncSnapshot() = TargetSyncSnapshot(
        whatsappWasCurrent = whatsappSyncedDataVersion == imageDataVersion,
        // A legacy partial push has no stored stamp, but while the local pack
        // is still unedited its remote subset necessarily represents this
        // revision. Capturing that lets an undo restore Partial, not dirty.
        telegramWasCurrent = telegramRevisionBeforeMutation() == imageDataVersion,
    )

    /** Revisions never move backwards, including undo. If the target matched
     * the exact pre-edit content, undo makes the new monotonic revision match
     * it again; a target that was already dirty stays dirty. */
    private fun PackEntity.bumpRevision(restoring: TargetSyncSnapshot? = null): PackEntity {
        val next = imageDataVersion + 1
        return copy(
            imageDataVersion = next,
            updatedAtMillis = System.currentTimeMillis(),
            whatsappSyncedDataVersion = if (restoring?.whatsappWasCurrent == true) {
                next
            } else {
                whatsappSyncedDataVersion
            },
            telegramSyncedDataVersion = if (restoring?.telegramWasCurrent == true) {
                next
            } else {
                telegramRevisionBeforeMutation()
            },
        )
    }

    private fun StickerEntity.mediaType(): StickerMediaType {
        if (isVideo) return StickerMediaType.Video
        val classified = StickerTypeClassifier.classify(sniffedContentType)
        if (classified != StickerMediaType.Unknown) return classified
        val original = originalFilePath?.let(::File)?.takeIf(File::exists)
        return original?.let(StickerTypeClassifier::reclassifyUnknown) ?: StickerMediaType.Static
    }

    private fun StickerEntity.mediaCrop(): MediaCrop? {
        val left = cropLeft ?: return null
        val top = cropTop ?: return null
        val right = cropRight ?: return null
        val bottom = cropBottom ?: return null
        return MediaCrop(left, top, right, bottom)
    }

    private fun PackEntity.canForkForLocalEdits(): Boolean =
        status == PackStatus.Ready.name &&
            (origin == PackOrigin.Imported.name || sourceUrl != null || sourceSignature != null)

    /** Copies only a file the app already owns under its packs tree. A non-null
     * DB path is a required reference: missing or external input fails the
     * whole fork instead of creating a clone with silently broken content. */
    private fun copyForkAsset(
        packsRoot: File,
        sourcePath: String?,
        destination: File,
    ): String? {
        if (sourcePath == null) return null
        val root = packsRoot.canonicalFile
        val source = File(sourcePath).canonicalFile
        val prefix = root.path + File.separator
        check(source.path.startsWith(prefix) && source.isFile) {
            "Pack asset is missing or outside app storage."
        }
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = false)
        return destination.absolutePath
    }

    /** The candidate is a UUID child created by this operation. Resolve it
     * again before recursive cleanup so a malformed/computed path can never
     * widen deletion beyond the packs directory. */
    private fun deleteForkDirectory(packsRoot: File, candidate: File) {
        val root = runCatching { packsRoot.canonicalFile }.getOrNull() ?: return
        val directory = runCatching { candidate.canonicalFile }.getOrNull() ?: return
        if (directory.parentFile == root) directory.deleteRecursively()
    }

    /** Only files underneath this pack may be reclaimed. A picked content URI
     * or any unexpected external path remains the user's property. */
    private fun deleteOwnedPackFiles(packDir: File, paths: List<String>) {
        val root = runCatching { packDir.canonicalFile }.getOrNull() ?: return
        val prefix = root.path + File.separator
        paths.distinct().forEach { path ->
            val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
            if (file.path.startsWith(prefix) && file.isFile) file.delete()
        }
    }

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
                trimStartMs = sticker.trimStartMs,
                trimDurationMs = sticker.trimDurationMs,
                crop = sticker.crop,
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

        /** Exact static subset left behind after a mixed import is split.
         * Without its own sentinel, update replay mistakes it for the source
         * part and restores the animations that were intentionally moved. */
        const val STATIC_SPLIT_PART_INDEX = -3
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
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val source = uri.path?.let(::File)
            if (source != null && source.canonicalFile == output.canonicalFile) {
                return source.isFile && source.length() > 0L
            }
        }
        output.parentFile?.mkdirs()
        val opened = appContext.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().use { out -> input.copyTo(out) }
        }
        opened != null
    } catch (_: Exception) {
        false
    }

    /** Reclaims only coordinator-owned temporary input after it has been
     * staged into a pack. Durable pack files and arbitrary file URIs are never
     * touched. */
    private fun deletePreparedInput(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return
        val root = runCatching { File(appContext.cacheDir, "picked").canonicalFile }.getOrNull()
            ?: return
        val file = uri.path?.let(::File)?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return
        if (file.path.startsWith(root.path + File.separator)) file.delete()
    }

    private suspend fun updatePack(packId: String, transform: (PackEntity) -> PackEntity) {
        val current = packDao.getPack(packId) ?: return
        packDao.upsert(transform(current).copy(updatedAtMillis = System.currentTimeMillis()))
    }

    private suspend fun finalizePackFailed(packId: String, message: String) {
        updatePack(packId) { it.copy(status = PackStatus.Failed.name, errorMessage = message) }
    }

    internal suspend fun finalizePackReady(
        packId: String,
        isAnimated: Boolean,
        trayIconPath: String?,
        warning: String? = null,
        bias: ConversionBias? = null,
        /** What conversion actually produced. The count written at download
         * time is the intended one, and a sticker the decoder refuses would
         * otherwise leave the pack advertising more than it holds. */
        stickerCount: Int? = null,
        /** Updated only when this operation successfully rendered a new tray.
         * Telegram-only publishes retain the user's existing choice. */
        trayStickerRowId: Long? = null,
        /** Telegram bookkeeping does not change anything served locally or
         * invalidate an otherwise-current WhatsApp installation. */
        bumpContentRevision: Boolean = true,
        /** Publish operations snapshot their input revision. Imports do not
         * expose concurrent local mutations and leave this unset. */
        expectedRevision: Int? = null,
        /** The remote push succeeded for at least one sticker. Fold its final
         * revision acknowledgement into this same commit as any local bump. */
        acknowledgeTelegram: Boolean = false,
        /** Supplied only by a successful full imported-pack conversion. A
         * partial/local mutation must not claim that every asset was rebuilt
         * by the current app. */
        convertedAppVersionCode: Int? = null,
        convertedAppVersionName: String? = null,
    ): Int? = database.withTransaction {
        val current = packDao.getPack(packId) ?: return@withTransaction null
        if (!canFinalizePublish(expectedRevision, current.imageDataVersion)) {
            return@withTransaction null
        }
        val revisioned = if (bumpContentRevision) current.bumpRevision() else current
        val updated = revisioned.copy(
                status = PackStatus.Ready.name,
                updatedAtMillis = System.currentTimeMillis(),
                isAnimatedPack = isAnimated,
                stickerCount = stickerCount ?: current.stickerCount,
                trayIconPath = trayIconPath ?: current.trayIconPath,
                trayStickerRowId = trayStickerRowId ?: current.trayStickerRowId,
                warningMessage = warning,
                errorMessage = null,
                // Only meaningful for an animated pack: the knob picks how
                // far the encoder may trade quality for frames, and a static
                // sticker has no frames to trade.
                conversionBias = bias?.name.takeIf { isAnimated },
                convertedAppVersionCode = convertedAppVersionCode
                    ?: current.convertedAppVersionCode,
                convertedAppVersionName = convertedAppVersionName
                    ?: current.convertedAppVersionName,
            )
        val committed = if (acknowledgeTelegram && updated.telegramSetName != null) {
            updated.copy(telegramSyncedDataVersion = updated.imageDataVersion)
        } else {
            updated
        }
        packDao.upsert(committed)
        committed.imageDataVersion
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
