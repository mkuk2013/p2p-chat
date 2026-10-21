package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdMs ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM outbox WHERE messageId = :messageId")
    suspend fun getById(messageId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE nextAttemptAtMs <= :nowMs ORDER BY createdMs ASC LIMIT :limit")
    suspend fun getDue(nowMs: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdMs ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity)

    @Upsert
    suspend fun upsert(entry: OutboxEntity)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAtMs = :nextAttemptAtMs WHERE messageId = :messageId")
    suspend fun recordAttempt(messageId: String, nextAttemptAtMs: Long)

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM outbox WHERE createdMs < :beforeMs")
    suspend fun deleteCreatedBefore(beforeMs: Long)

    @Query("DELETE FROM outbox")
    suspend fun clearAll()
}
