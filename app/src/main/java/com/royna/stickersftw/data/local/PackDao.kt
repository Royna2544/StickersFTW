package com.royna.stickersftw.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PackDao {
    @Transaction
    @Query("SELECT * FROM packs ORDER BY isPinned DESC, updatedAtMillis DESC")
    fun observePacksWithStickers(): Flow<List<PackWithStickers>>

    @Transaction
    @Query("SELECT * FROM packs WHERE id = :id")
    fun observePackWithStickers(id: String): Flow<PackWithStickers?>

    @Query("SELECT * FROM packs WHERE id = :id")
    suspend fun getPack(id: String): PackEntity?

    // Blocking (non-suspend) reads, used only by StickerContentProvider --
    // WhatsApp calls the provider from Binder threads in its own process,
    // never this process's main thread, so a blocking query here is safe
    // and matches WhatsApp's own sample provider's synchronous SQLite reads.
    @Query("SELECT * FROM packs WHERE status = 'Ready'")
    fun getReadyPacksBlocking(): List<PackEntity>

    @Query("SELECT * FROM packs WHERE id = :id AND status = 'Ready'")
    fun getReadyPackBlocking(id: String): PackEntity?

    @Upsert
    suspend fun upsert(pack: PackEntity)

    @Query("UPDATE packs SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE packs SET whatsappAdded = :added WHERE id = :id")
    suspend fun setWhatsappAdded(id: String, added: Boolean)

    /** Called only after the explicit Add-to-WhatsApp result has been
     * verified against WhatsApp's whitelist. A passive whitelist refresh
     * must use [setWhatsappAdded] so it cannot make edited content current. */
    @Query(
        "UPDATE packs SET whatsappAdded = 1, whatsappSyncedDataVersion = :expectedRevision " +
            "WHERE id = :id AND imageDataVersion = :expectedRevision",
    )
    suspend fun acknowledgeWhatsappInstall(id: String, expectedRevision: Int): Int

    @Query(
        "UPDATE packs SET telegramSetName = :fullName, " +
            "telegramSyncedDataVersion = COALESCE(telegramSyncedDataVersion, :representedRevision), " +
            "updatedAtMillis = :now WHERE id = :id",
    )
    suspend fun setTelegramSetName(
        id: String,
        fullName: String,
        representedRevision: Int,
        now: Long,
    ): Int

    /** Non-terminal states only exist while an operation is running. If one
     * is found with nothing running, the process died mid-conversion and the
     * pack would otherwise sit at "Downloading" forever. */
    @Query(
        "UPDATE packs SET status = 'Failed', errorMessage = :message " +
            "WHERE status IN ('Building', 'Downloading', 'Converting')",
    )
    suspend fun failUnfinished(message: String): Int

    @Query("SELECT id FROM packs WHERE status IN ('Building', 'Downloading', 'Converting')")
    suspend fun unfinishedIds(): List<String>

    @Query("DELETE FROM packs WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "SELECT * FROM packs WHERE origin = 'Imported' AND status = 'Ready' AND updateCheckEnabled = 1",
    )
    suspend fun getUpdateCheckCandidates(): List<PackEntity>

    @Query(
        "SELECT * FROM packs WHERE origin = 'Imported' AND telegramSetName = :setName",
    )
    suspend fun getImportedPacksForSet(setName: String): List<PackEntity>

    @Query("UPDATE packs SET updateAvailable = :available WHERE id = :id")
    suspend fun setUpdateAvailable(id: String, available: Boolean)

    /** Refreshes an equivalent legacy signature to the latest stable format
     * while clearing a stale dot. The snapshot guard prevents a network
     * response started for an older revision from overwriting a newer pack. */
    @Query(
        "UPDATE packs SET sourceSignature = :signature, updateAvailable = 0 " +
            "WHERE id = :id AND imageDataVersion = :expectedRevision AND " +
            "((sourceSignature = :expectedSignature) OR " +
            "(sourceSignature IS NULL AND :expectedSignature IS NULL))",
    )
    suspend fun markSourceCurrentIfUnchanged(
        id: String,
        signature: String,
        expectedSignature: String?,
        expectedRevision: Int,
    ): Int

    @Query(
        "UPDATE packs SET updateAvailable = :available " +
            "WHERE id = :id AND imageDataVersion = :expectedRevision AND " +
            "((sourceSignature = :expectedSignature) OR " +
            "(sourceSignature IS NULL AND :expectedSignature IS NULL))",
    )
    suspend fun setUpdateAvailableIfUnchanged(
        id: String,
        available: Boolean,
        expectedSignature: String?,
        expectedRevision: Int,
    ): Int

    @Query(
        "UPDATE packs SET updateCheckEnabled = :enabled, updateAvailable = CASE WHEN :enabled THEN updateAvailable ELSE 0 END WHERE id = :id",
    )
    suspend fun setUpdateCheckEnabled(id: String, enabled: Boolean)

    @Query(
        "UPDATE packs SET updateAvailable = 0, sourceSignature = :signature, importPartIndex = :partIndex, updatedAtMillis = :now WHERE id = :id",
    )
    suspend fun applyUpdateBaseline(id: String, signature: String, partIndex: Int, now: Long)
}
