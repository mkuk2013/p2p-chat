package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.VoiceTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceTransferDao {
    @Upsert
    suspend fun upsert(transfer: VoiceTransferEntity)

    @Query("SELECT * FROM voice_transfers WHERE blobId = :blobId")
    suspend fun getById(blobId: String): VoiceTransferEntity?

    @Query("SELECT * FROM voice_transfers WHERE blobId = :blobId")
    fun observeById(blobId: String): Flow<VoiceTransferEntity?>

    @Query("SELECT * FROM voice_transfers WHERE conversationPeerId = :peerId ORDER BY createdAtMs ASC")
    fun observeByConversation(peerId: String): Flow<List<VoiceTransferEntity>>

    @Query(
        "UPDATE voice_transfers SET completedChunks = :completed, state = :state " +
            "WHERE blobId = :blobId"
    )
    suspend fun updateProgress(blobId: String, completed: Int, state: String)

    @Query("UPDATE voice_transfers SET state = :state, filePath = :filePath WHERE blobId = :blobId")
    suspend fun updateStateAndFile(blobId: String, state: String, filePath: String?)

    @Query("DELETE FROM voice_transfers WHERE blobId = :blobId")
    suspend fun deleteById(blobId: String)

    @Query("DELETE FROM voice_transfers")
    suspend fun clearAll()
}
