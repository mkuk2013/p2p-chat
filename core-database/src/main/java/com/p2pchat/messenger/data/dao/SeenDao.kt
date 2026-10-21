package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.p2pchat.messenger.data.entity.SeenEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SeenEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_messages WHERE messageId = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Query("SELECT COUNT(*) FROM seen_messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM seen_messages")
    suspend fun count(): Int

    @Query("DELETE FROM seen_messages WHERE seenAtMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM seen_messages")
    suspend fun clearAll()
}
