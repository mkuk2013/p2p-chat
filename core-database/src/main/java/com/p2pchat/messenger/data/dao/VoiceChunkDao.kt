package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.VoiceChunkEntity

@Dao
interface VoiceChunkDao {
    @Upsert
    suspend fun upsert(chunk: VoiceChunkEntity)

    /** Incoming chunk store: first copy wins, duplicates are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(chunk: VoiceChunkEntity): Long

    @Query("SELECT * FROM voice_chunks WHERE blobId = :blobId AND chunkIndex = :chunkIndex")
    suspend fun get(blobId: String, chunkIndex: Int): VoiceChunkEntity?

    @Query("SELECT * FROM voice_chunks WHERE blobId = :blobId ORDER BY chunkIndex ASC")
    suspend fun getAllForBlob(blobId: String): List<VoiceChunkEntity>

    @Query("SELECT * FROM voice_chunks WHERE envelopeMessageId = :envelopeMessageId")
    suspend fun getByEnvelopeId(envelopeMessageId: String): VoiceChunkEntity?

    @Query("SELECT COUNT(*) FROM voice_chunks WHERE blobId = :blobId AND state = :state")
    suspend fun countByState(blobId: String, state: String): Int

    @Query("UPDATE voice_chunks SET state = :state WHERE blobId = :blobId AND chunkIndex = :chunkIndex")
    suspend fun updateState(blobId: String, chunkIndex: Int, state: String)

    @Query(
        "UPDATE voice_chunks SET state = :state, envelopeMessageId = :envelopeMessageId " +
            "WHERE blobId = :blobId AND chunkIndex = :chunkIndex"
    )
    suspend fun updateStateAndEnvelope(blobId: String, chunkIndex: Int, state: String, envelopeMessageId: String)

    @Query("DELETE FROM voice_chunks WHERE blobId = :blobId")
    suspend fun deleteForBlob(blobId: String)

    @Query("DELETE FROM voice_chunks")
    suspend fun clearAll()
}
