package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationPeerId = :peerId ORDER BY timestampMs ASC")
    fun observeByConversation(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    fun observeById(messageId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET deliveryState = :state WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: String)

    @Query("UPDATE messages SET deliveryState = :state, hopCount = :hopCount WHERE messageId = :messageId")
    suspend fun updateDeliveryStateAndHops(messageId: String, state: String, hopCount: Int)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationPeerId = :peerId")
    suspend fun deleteByConversation(peerId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
