package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE displayName LIKE '%' || :query || '%' " +
            "OR lastMessagePreview LIKE '%' || :query || '%' ORDER BY lastMessageMs DESC"
    )
    fun search(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerId = :peerId")
    suspend fun getById(peerId: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE peerId = :peerId")
    suspend fun clearUnread(peerId: String)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE peerId = :peerId")
    suspend fun incrementUnread(peerId: String)

    @Query("DELETE FROM conversations WHERE peerId = :peerId")
    suspend fun deleteById(peerId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()
}
