package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.p2pchat.messenger.data.entity.CallRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Insert
    suspend fun insert(record: CallRecordEntity)

    /** Newest first — the Calls tab renders this order directly. */
    @Query("SELECT * FROM call_records ORDER BY startedAtMs DESC, id DESC")
    fun observeAll(): Flow<List<CallRecordEntity>>

    @Query("DELETE FROM call_records")
    suspend fun clearAll()
}
