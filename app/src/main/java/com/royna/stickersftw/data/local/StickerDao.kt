package com.royna.stickersftw.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StickerDao {
    @Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY position")
    fun observeStickers(packId: String): Flow<List<StickerEntity>>

    // Blocking read, used only by StickerContentProvider -- see PackDao.
    @Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY position")
    fun getStickersBlocking(packId: String): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE packId = :packId ORDER BY position")
    suspend fun getStickersOnce(packId: String): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE packId = :packId AND remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(packId: String, remoteId: String): StickerEntity?

    @Query("SELECT * FROM stickers WHERE rowId = :rowId LIMIT 1")
    suspend fun findByRowId(rowId: Long): StickerEntity?

    @Query("SELECT * FROM stickers WHERE packId = :packId AND rowId = :rowId LIMIT 1")
    suspend fun findByRowIdInPack(packId: String, rowId: Long): StickerEntity?

    @Upsert
    suspend fun upsertAll(stickers: List<StickerEntity>)

    @Query("DELETE FROM stickers WHERE rowId = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Upsert
    suspend fun upsert(sticker: StickerEntity): Long

    @Query("DELETE FROM stickers WHERE packId = :packId")
    suspend fun deleteForPack(packId: String)
}
